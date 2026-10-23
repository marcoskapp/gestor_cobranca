package br.com.gestorcobranca.communication.application.service;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.communication.application.port.in.MessageUseCases;
import br.com.gestorcobranca.communication.domain.model.ChannelAccount;
import br.com.gestorcobranca.communication.domain.model.ChannelType;
import br.com.gestorcobranca.communication.domain.model.Conversation;
import br.com.gestorcobranca.communication.domain.model.Message;
import br.com.gestorcobranca.communication.domain.model.MessageTemplate;
import br.com.gestorcobranca.communication.domain.model.OwnerType;
import br.com.gestorcobranca.communication.domain.model.SenderType;
import br.com.gestorcobranca.communication.domain.repository.ConversationRepository;
import br.com.gestorcobranca.communication.domain.repository.MessageRepository;
import br.com.gestorcobranca.communication.domain.repository.MessageTemplateRepository;
import br.com.gestorcobranca.communication.domain.service.TemplateRenderer;
import br.com.gestorcobranca.compliance.application.port.in.ContactGate;
import br.com.gestorcobranca.compliance.application.port.in.ContactGate.ContactDecision;
import br.com.gestorcobranca.compliance.application.port.in.ContactGate.ContactRequest;
import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases.ChannelContact;
import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases.ContactFingerprint;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
class MessageService implements MessageUseCases {

    private final MessageRepository messages;
    private final ConversationRepository conversations;
    private final MessageTemplateRepository templates;
    private final TemplateVariables templateVariables;
    private final ChannelResolver channels;
    private final ConversationAccess access;
    private final ConversationOwnership ownership;
    private final MessageRecorder recorder;
    private final ContactGate gate;
    private final DebtorUseCases debtors;
    private final DebtorContactUseCases debtorContacts;
    private final CaseReader cases;
    private final AccessScope accessScope;
    private final JobQueue jobQueue;
    private final CommunicationMetrics metrics;
    private final Clock clock;

    MessageService(MessageRepository messages, ConversationRepository conversations, MessageTemplateRepository templates,
                   TemplateVariables templateVariables, ChannelResolver channels, ConversationAccess access,
                   ConversationOwnership ownership, MessageRecorder recorder, ContactGate gate, DebtorUseCases debtors,
                   DebtorContactUseCases debtorContacts, CaseReader cases, AccessScope accessScope, JobQueue jobQueue,
                   CommunicationMetrics metrics, Clock clock) {
        this.messages = messages;
        this.conversations = conversations;
        this.templates = templates;
        this.templateVariables = templateVariables;
        this.channels = channels;
        this.access = access;
        this.ownership = ownership;
        this.recorder = recorder;
        this.gate = gate;
        this.debtors = debtors;
        this.debtorContacts = debtorContacts;
        this.cases = cases;
        this.accessScope = accessScope;
        this.jobQueue = jobQueue;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public MessageView send(SendMessageCommand command) {
        Guard.require(command != null, "message.required", "Dados da mensagem obrigatórios");
        String idempotencyKey = command.idempotencyKey() == null || command.idempotencyKey().isBlank() ? null
                : command.idempotencyKey().trim();
        if (idempotencyKey != null) {
            Optional<Message> existing = messages.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                access.visible(existing.get().getConversationId());
                return CommunicationViews.message(existing.get());
            }
        }
        SenderType sender = access.senderOf(command.campaignExecutionId());
        Guard.require(command.campaignExecutionId() == null || sender == SenderType.CAMPAIGN,
                "message.campaign_requires_system", "Envio de campanha é feito pelo processo da campanha");
        Conversation conversation = command.conversationId() != null ? access.forCommand(command.conversationId())
                : openConversation(command);
        Content content = content(conversation, sender, command.templateCode(), command.variables(), command.text());
        Message message = queue(conversation, sender, content, command.campaignExecutionId(), null, idempotencyKey);
        return CommunicationViews.message(message);
    }

    @Override
    @Transactional(readOnly = true)
    public MessageView get(UUID messageId) {
        Message m = messages.findById(messageId).orElseThrow(() -> ResourceNotFoundException.of("message", messageId));
        Conversation c = conversations.findById(m.getConversationId())
                .orElseThrow(() -> ResourceNotFoundException.of("message", messageId));
        try {
            access.requireVisible(c);
        } catch (ResourceNotFoundException e) {
            throw ResourceNotFoundException.of("message", messageId);
        }
        return CommunicationViews.message(m);
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<MessageView> listByConversation(UUID conversationId, PageQuery page) {
        Conversation c = access.visible(conversationId);
        return PageResponse.of(messages.findByConversationIdOrderByCreatedAtDesc(c.getId(), page.toPageable()),
                CommunicationViews::message);
    }

    /** Conteúdo pronto para envio. @param template nulo = texto livre */
    record Content(MessageTemplate template, String subject, String body, String variablesJson) {
    }

    /**
     * Valida autor e conteúdo e enfileira na conversa (já carregada com lock). O operador que pode
     * trabalhar a conversa passa a ser o dono; o assistente só envia em conversa que conduz.
     */
    Message queue(Conversation conversation, SenderType sender, Content content, UUID campaignExecutionId, UUID draftId,
                  String idempotencyKey) {
        Guard.requireState(conversation.isOpen(), "conversation.closed", "Conversa encerrada; inicie uma nova");
        if (conversation.getDebtorId() == null || conversation.getCreditorId() == null) {
            throw new ConflictException("conversation.unidentified",
                    "Identifique o devedor e o credor da conversa antes de enviar mensagens");
        }
        Actor actor = access.actor();
        Conversation c = sender == SenderType.USER && access.requireWorkable(conversation)
                ? ownership.takeOver(conversation, "Envio de mensagem") : conversation;
        if (sender == SenderType.ASSISTANT && c.getOwnerType() != OwnerType.ASSISTANT) {
            throw new ConflictException("conversation.not_with_assistant",
                    "O assistente só envia em conversas que conduz");
        }
        Instant now = Instant.now(clock);
        if (content.template() == null) {
            Guard.require(c.getChannel() == ChannelType.WHATSAPP, "message.template_required",
                    "E-mail e SMS só com template");
            Guard.require(sender == SenderType.USER || sender == SenderType.ASSISTANT, "message.template_required",
                    "Campanhas e envios automáticos só com template");
            if (!c.sessionWindowOpen(now)) {
                throw new ConflictException("message.whatsapp_window_closed",
                        "Fora da janela de 24 h do WhatsApp: use um template aprovado");
            }
        }
        UUID senderId = sender == SenderType.CAMPAIGN || sender == SenderType.SYSTEM ? null : actor.id();
        Message message = Message.outbound(c, new Message.Outbound(sender, senderId,
                content.template() == null ? null : content.template().getId(), content.variablesJson(),
                content.subject(), content.body(), campaignExecutionId, draftId, idempotencyKey), now);

        // verificação prévia: pedido já negado é registrado como falha, sem enfileirar
        ContactDecision decision = gate.evaluate(gateRequest(c, message, sender));
        if (!decision.allowed()) {
            List<String> reasons = decision.reasons().stream().map(Enum::name).toList();
            message.markBlocked(String.join(",", reasons), now);
            Message saved = messages.save(message);
            recorder.blocked(saved, c, reasons, "precheck");
            return saved;
        }
        c.registerOutbound(sender, now).ifPresent(waited ->
                metrics.firstResponse(c.getChannel().name(), sender.name(), waited));
        conversations.save(c);
        Message saved = messages.saveAndFlush(message);
        jobQueue.enqueue(SendMessageJob.TYPE, new SendMessageJob.Payload(saved.getId()), null, "message:" + saved.getId());
        recorder.queued(saved, c);
        return saved;
    }

    /** Pedido ao porteiro para a mensagem (verificação prévia e autorização no envio). */
    static ContactRequest gateRequest(Conversation c, Message m, SenderType sender) {
        String origin = switch (sender) {
            case USER -> "OPERATOR";
            case CAMPAIGN -> "CAMPAIGN";
            default -> "SYSTEM";
        };
        return new ContactRequest(c.getDebtorId(), c.getCreditorId(), c.getChannel().name(), c.getContactValue(), null,
                origin, "message", m.getId());
    }

    Content content(Conversation c, SenderType sender, String templateCode, Map<String, String> values, String text) {
        boolean hasTemplate = templateCode != null && !templateCode.isBlank();
        boolean hasText = text != null && !text.isBlank();
        Guard.require(hasTemplate != hasText, "message.content_invalid", "Informe o template ou o texto (um dos dois)");
        if (hasText) {
            Guard.require(values == null || values.isEmpty(), "message.variables_without_template",
                    "Variáveis só com template");
            return new Content(null, null, text, null);
        }
        String code = templateCode.trim().toUpperCase(Locale.ROOT);
        MessageTemplate template = templates.findPublished(c.getCreditorId(), code)
                .or(() -> templates.findPublished(null, code))
                .orElseThrow(() -> new BusinessRuleException("message.template_not_found",
                        "Template publicado inexistente: " + code));
        Guard.require(template.getChannel() == c.getChannel(), "message.template_channel_mismatch",
                "O template %s é do canal %s".formatted(code, template.getChannel()));
        TemplateRenderer.Rendered rendered = TemplateRenderer.render(template.getSubject(), template.getBody(),
                templateVariables.parse(template.getVariables()), values);
        return new Content(template, rendered.subject(), rendered.body(), templateVariables.valuesToJson(values));
    }

    /** Conversa aberta do contato do devedor no credor (ou nova), na conta do credor ou da assessoria. */
    private Conversation openConversation(SendMessageCommand command) {
        Guard.require(command.debtorId() != null && command.creditorId() != null, "message.target_required",
                "Informe a conversa ou o devedor e o credor");
        ChannelType channel = CommunicationEnums.parse(ChannelType.class, command.channel(), "message.channel_invalid",
                "Canal");
        accessScope.requireCreditor(command.creditorId());
        debtors.get(command.debtorId());
        ChannelAccount account = channels.resolve(channel, command.creditorId());
        ChannelContact contact = contact(command.debtorId(), channel, command.contactId());
        ContactFingerprint fingerprint = debtorContacts.fingerprint(contact.type(), contact.value());
        Optional<CaseView> kase = caseFor(command.debtorId(), command.creditorId(), command.caseId());
        Optional<Conversation> open = conversations.findOpen(account.getId(), fingerprint.hash(), command.creditorId());
        if (open.isPresent()) {
            Conversation c = conversations.findByIdForUpdate(open.get().getId()).orElseThrow();
            Guard.requireState(command.debtorId().equals(c.getDebtorId()) || c.getDebtorId() == null,
                    "conversation.contact_of_other_debtor", "O contato está numa conversa de outro devedor");
            if (c.getDebtorId() == null || (c.getCaseId() == null && kase.isPresent())) {
                c.linkCase(command.debtorId(), kase.map(CaseView::portfolioId).orElse(null), kase.map(CaseView::id).orElse(null));
            }
            c.useContact(contact.contactId());
            return conversations.save(c);
        }
        Conversation.Owner owner = kase.map(k -> ConversationOwnership.fromCase(k, (String) null, false))
                .orElse(Conversation.Owner.NONE);
        return conversations.saveAndFlush(new Conversation(command.debtorId(), command.creditorId(),
                kase.map(CaseView::portfolioId).orElse(null), kase.map(CaseView::id).orElse(null), channel,
                account.getId(), contact.contactId(), new Conversation.Contact(contact.value(), fingerprint.hash(),
                fingerprint.maskedValue()), owner, Instant.now(clock)));
    }

    private ChannelContact contact(UUID debtorId, ChannelType channel, UUID contactId) {
        String type = channel == ChannelType.EMAIL ? "EMAIL" : "PHONE";
        List<ChannelContact> usable = debtorContacts.contactsForChannel(debtorId, type);
        if (contactId != null) {
            return usable.stream().filter(c -> c.contactId().equals(contactId)).findFirst()
                    .orElseThrow(() -> new BusinessRuleException("message.contact_unusable",
                            "Contato inexistente, de outro devedor ou inválido para o canal"));
        }
        return usable.stream()
                .filter(c -> channel != ChannelType.WHATSAPP || c.whatsapp())
                .filter(c -> channel == ChannelType.EMAIL || "MOBILE".equals(c.label()) || c.whatsapp())
                .findFirst()
                .orElseThrow(() -> new BusinessRuleException("message.no_contact",
                        "O devedor não tem contato utilizável para o canal %s".formatted(channel)));
    }

    /** Caso informado (do devedor, no credor, ativo) ou o único caso ativo do devedor no credor. */
    private Optional<CaseView> caseFor(UUID debtorId, UUID creditorId, UUID caseId) {
        if (caseId != null) {
            CaseView kase = cases.get(caseId);
            Guard.require(kase.debtorId().equals(debtorId) && kase.creditorId().equals(creditorId),
                    "message.case_mismatch", "O caso não é do devedor no credor informado");
            Guard.requireState(!"CLOSED".equals(kase.status()), "case.closed", "Caso encerrado");
            return Optional.of(kase);
        }
        return cases.singleActive(debtorId, creditorId);
    }
}
