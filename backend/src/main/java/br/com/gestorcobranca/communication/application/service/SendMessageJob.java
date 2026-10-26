package br.com.gestorcobranca.communication.application.service;

import br.com.gestorcobranca.communication.application.CommunicationProperties;
import br.com.gestorcobranca.communication.application.port.out.ChannelGateway;
import br.com.gestorcobranca.communication.application.port.out.ChannelGateway.OutboundRequest;
import br.com.gestorcobranca.communication.application.port.out.ChannelGateway.ProviderTemplate;
import br.com.gestorcobranca.communication.application.port.out.ChannelGateway.SendResult;
import br.com.gestorcobranca.communication.application.port.out.ChannelGateway.TransientSendException;
import br.com.gestorcobranca.communication.domain.model.ChannelAccount;
import br.com.gestorcobranca.communication.domain.model.Conversation;
import br.com.gestorcobranca.communication.domain.model.Message;
import br.com.gestorcobranca.communication.domain.model.MessageStatus;
import br.com.gestorcobranca.communication.domain.model.MessageTemplate;
import br.com.gestorcobranca.communication.domain.repository.ChannelAccountRepository;
import br.com.gestorcobranca.communication.domain.repository.ConversationRepository;
import br.com.gestorcobranca.communication.domain.repository.MessageRepository;
import br.com.gestorcobranca.communication.domain.repository.MessageTemplateRepository;
import br.com.gestorcobranca.communication.domain.service.TemplateRenderer;
import br.com.gestorcobranca.compliance.application.port.in.ContactGate;
import br.com.gestorcobranca.compliance.application.port.in.ContactGate.ContactDecision;
import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Envio de uma mensagem enfileirada ({@code communication.send}), no tenant da mensagem, como ator
 * de sistema, numa transação:
 * <ol>
 *   <li>mensagem que não está mais {@code QUEUED} → nada a fazer (reexecução idempotente);</li>
 *   <li>conta inativa/sem adaptador ou janela do WhatsApp fechada para texto livre → {@code FAILED};</li>
 *   <li>{@code ContactGate#authorizeAndRecord} imediatamente antes do envio: negado →
 *       {@code FAILED/CONTACT_DENIED} (sem retentativa); permitido → tentativa registrada;</li>
 *   <li>provedor: aceite → {@code SENT}; recusa definitiva → {@code FAILED}; falha transitória →
 *       exceção (a transação é desfeita, inclusive a tentativa no compliance, e o job é reagendado).
 *       Na última tentativa a mensagem é marcada {@code FAILED/RETRIES_EXHAUSTED} em transação
 *       própria e o job vai para {@code DEAD}.</li>
 * </ol>
 * Envio aceito pelo provedor e commit que falha depois podem gerar reenvio (entrega
 * "ao menos uma vez"); o provedor não oferece chave de idempotência no envio.
 */
@Component
class SendMessageJob implements JobHandler<SendMessageJob.Payload> {

    static final String TYPE = "communication.send";
    private static final Logger LOG = LoggerFactory.getLogger(SendMessageJob.class);

    private final MessageRepository messages;
    private final ConversationRepository conversations;
    private final ChannelAccountRepository accounts;
    private final MessageTemplateRepository templates;
    private final TemplateVariables templateVariables;
    private final CredentialCodec credentialCodec;
    private final ChannelResolver channels;
    private final ContactGate gate;
    private final MessageRecorder recorder;
    private final MessageFailureRecorder failureRecorder;
    private final CommunicationMetrics metrics;
    private final CommunicationProperties properties;
    private final Clock clock;

    SendMessageJob(MessageRepository messages, ConversationRepository conversations, ChannelAccountRepository accounts,
                   MessageTemplateRepository templates, TemplateVariables templateVariables,
                   CredentialCodec credentialCodec, ChannelResolver channels, ContactGate gate, MessageRecorder recorder,
                   MessageFailureRecorder failureRecorder, CommunicationMetrics metrics,
                   CommunicationProperties properties, Clock clock) {
        this.messages = messages;
        this.conversations = conversations;
        this.accounts = accounts;
        this.templates = templates;
        this.templateVariables = templateVariables;
        this.credentialCodec = credentialCodec;
        this.channels = channels;
        this.gate = gate;
        this.recorder = recorder;
        this.failureRecorder = failureRecorder;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    record Payload(UUID messageId) {
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Class<Payload> payloadType() {
        return Payload.class;
    }

    @Override
    public int maxAttempts() {
        return properties.sendMaxAttempts();
    }

    @Override
    public void handle(JobContext context, Payload payload) {
        Optional<Message> found = messages.findById(payload.messageId());
        if (found.isEmpty() || found.get().getStatus() != MessageStatus.QUEUED) {
            return;
        }
        Message message = found.get();
        Conversation conversation = conversations.findById(message.getConversationId()).orElseThrow();
        Optional<ChannelAccount> account = accounts.findById(message.getChannelAccountId()).filter(ChannelAccount::isActive);
        Optional<ChannelGateway> gateway = account.flatMap(a -> channels.gateway(a.getProvider()));
        String provider = account.map(a -> a.getProvider().name()).orElse("none");
        Instant now = Instant.now(clock);
        if (gateway.isEmpty()) {
            fail(message, conversation, context, "ACCOUNT_UNAVAILABLE", "Conta de canal inativa ou sem adaptador", provider);
            return;
        }
        if (message.getTemplateId() == null && !conversation.sessionWindowOpen(now)) {
            fail(message, conversation, context, "WHATSAPP_WINDOW_CLOSED",
                    "Janela de 24 h do WhatsApp encerrada antes do envio", provider);
            return;
        }
        ContactDecision decision = gate.authorizeAndRecord(
                MessageService.gateRequest(conversation, message, message.getSenderType()));
        if (!decision.allowed()) {
            List<String> reasons = decision.reasons().stream().map(Enum::name).toList();
            message.recordAttempt(context.attempt());
            message.markBlocked(String.join(",", reasons), now);
            messages.save(message);
            metrics.sendAttempt(message.getChannel().name(), provider, "blocked");
            recorder.blocked(message, conversation, reasons, "send");
            return;
        }
        SendResult result;
        try {
            result = gateway.get().send(request(message, conversation, account.get()));
        } catch (TransientSendException e) {
            metrics.sendAttempt(message.getChannel().name(), provider, "transient");
            if (context.attempt() >= maxAttempts()) {
                failureRecorder.exhausted(message.getId(), context.attempt(), e.getMessage());
                metrics.jobFailure(TYPE);
            }
            throw e;
        }
        message.recordAttempt(context.attempt());
        Instant done = Instant.now(clock);
        if (result.accepted()) {
            message.markSent(result.providerMessageId(), decision.attemptId(), done);
            messages.save(message);
            recorder.sent(message, conversation, provider);
        } else {
            LOG.atWarn().addKeyValue("messageId", message.getId()).addKeyValue("provider", provider)
                    .addKeyValue("errorCode", result.errorCode()).log("Provedor recusou a mensagem");
            message.markFailed(result.errorCode(), result.errorDetail(), done);
            messages.save(message);
            metrics.sendAttempt(message.getChannel().name(), provider, "rejected");
            recorder.failed(message, conversation);
        }
    }

    private void fail(Message message, Conversation conversation, JobContext context, String code, String detail,
                      String provider) {
        message.recordAttempt(context.attempt());
        message.markFailed(code, detail, Instant.now(clock));
        messages.save(message);
        metrics.sendAttempt(message.getChannel().name(), provider, code.toLowerCase(java.util.Locale.ROOT));
        recorder.failed(message, conversation);
    }

    private OutboundRequest request(Message m, Conversation c, ChannelAccount account) {
        ProviderTemplate providerTemplate = null;
        if (m.getTemplateId() != null) {
            MessageTemplate template = templates.findById(m.getTemplateId()).orElseThrow();
            if (template.getProviderTemplateName() != null) {
                TemplateRenderer.Rendered rendered = TemplateRenderer.render(template.getSubject(), template.getBody(),
                        templateVariables.parse(template.getVariables()), templateVariables.parseValues(m.getVariables()));
                providerTemplate = template.isAuthentication()
                        ? ProviderTemplate.authentication(template.getProviderTemplateName(), template.getLanguage(),
                                rendered.parameters().getFirst())
                        : ProviderTemplate.standard(template.getProviderTemplateName(), template.getLanguage(),
                                rendered.parameters());
            }
        }
        return new OutboundRequest(account.getId(), credentialCodec.decode(account.getCredentials()),
                account.getSenderAddress(), account.getSenderName(), account.getReplyTo(), c.getContactValue(),
                m.getSubject(), m.getContent(), providerTemplate, m.getId());
    }
}
