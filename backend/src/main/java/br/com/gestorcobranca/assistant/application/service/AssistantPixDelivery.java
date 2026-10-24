package br.com.gestorcobranca.assistant.application.service;

import br.com.gestorcobranca.assistant.application.port.out.ConversationLocks;
import br.com.gestorcobranca.assistant.application.port.out.PaymentInstructionPort.PixInstruction;
import br.com.gestorcobranca.assistant.application.port.out.PaymentInstructionPort;
import br.com.gestorcobranca.assistant.domain.model.AssistantPolicy;
import br.com.gestorcobranca.assistant.domain.model.HandoffReason;
import br.com.gestorcobranca.assistant.domain.model.PixRequest;
import br.com.gestorcobranca.assistant.domain.model.PolicySettings;
import br.com.gestorcobranca.assistant.domain.model.SessionStatus;
import br.com.gestorcobranca.assistant.domain.repository.AssistantPolicyRepository;
import br.com.gestorcobranca.assistant.domain.repository.AssistantSessionRepository;
import br.com.gestorcobranca.assistant.domain.repository.PixRequestRepository;
import br.com.gestorcobranca.assistant.domain.service.PixMessage;
import br.com.gestorcobranca.communication.application.port.in.ConversationUseCases.HandoffCommand;
import br.com.gestorcobranca.communication.application.port.in.ConversationUseCases;
import br.com.gestorcobranca.communication.application.port.in.MessageUseCases.MessageView;
import br.com.gestorcobranca.communication.application.port.in.MessageUseCases.SendMessageCommand;
import br.com.gestorcobranca.communication.application.port.in.MessageUseCases;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.DomainException;
import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobHandler;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import br.com.gestorcobranca.shared.security.Actor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Entrega do Pix gerado pelo assistente (job {@code assistant.pix_delivery}): agendada quando o turno
 * respondeu ao devedor e de novo a cada mudança da instrução no payment ({@code PaymentInstructionStatusChanged}).
 * Sob o lock da conversa (serializa com os turnos), como o ator {@code ASSISTANT} da política do pedido:
 * <ul>
 *   <li>cobrança ainda {@code PENDING} no PSP → aguarda o evento de ativação;</li>
 *   <li>{@code ACTIVE} → mensagem do {@link PixMessage} (código validado, valor do motor, validade e aviso de
 *       segurança) pelo communication, com chave de idempotência por pedido; porteiro negou ou conversa já
 *       não está com o assistente → não entregue;</li>
 *   <li>recusada, expirada ou cancelada antes da entrega, ou código inválido → não entregue, mensagem padrão e
 *       transferência {@code PAYMENT_ASSISTANCE} (o devedor esperava o código).</li>
 * </ul>
 */
@Component
class AssistantPixDelivery implements JobHandler<AssistantPixDelivery.Payload> {

    static final String TYPE = "assistant.pix_delivery";
    private static final Logger LOG = LoggerFactory.getLogger(AssistantPixDelivery.class);

    record Payload(UUID pixRequestId) {
    }

    private final PixRequestRepository requests;
    private final AssistantPolicyRepository policies;
    private final AssistantSessionRepository sessions;
    private final ObjectProvider<PaymentInstructionPort> payments;
    private final MessageUseCases messages;
    private final ConversationUseCases conversations;
    private final AssistantActors actors;
    private final AssistantCodec codec;
    private final ConversationLocks locks;
    private final AssistantPixRecorder recorder;
    private final Clock clock;

    AssistantPixDelivery(PixRequestRepository requests, AssistantPolicyRepository policies,
                         AssistantSessionRepository sessions, ObjectProvider<PaymentInstructionPort> payments,
                         MessageUseCases messages, ConversationUseCases conversations, AssistantActors actors,
                         AssistantCodec codec, ConversationLocks locks, AssistantPixRecorder recorder, Clock clock) {
        this.requests = requests;
        this.policies = policies;
        this.sessions = sessions;
        this.payments = payments;
        this.messages = messages;
        this.conversations = conversations;
        this.actors = actors;
        this.codec = codec;
        this.locks = locks;
        this.recorder = recorder;
        this.clock = clock;
    }

    static void enqueue(JobQueue jobs, UUID pixRequestId) {
        jobs.enqueue(TYPE, new Payload(pixRequestId), null, "pix:" + pixRequestId);
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
    public void handle(JobContext context, Payload payload) {
        PixRequest r = requests.findByIdForUpdate(payload.pixRequestId()).orElse(null);
        if (r == null || r.getDeliveryStatus() == null || !r.getDeliveryStatus().open()) {
            return;
        }
        // serializa com os turnos da conversa (a mensagem do Pix nunca sai no meio de um turno)
        locks.lockConversation(r.getConversationId());
        AssistantPolicy policy = policies.findById(r.getPolicyId()).orElseThrow();
        PolicySettings settings = codec.settings(policy.getSettings());
        Actor actor = actors.forPolicy(context.tenantId(), policy, r.getMode(), r.getCreditorId(), true);
        PaymentInstructionPort port = payments.getObject();
        PixInstruction pix = actors.as(actor, () -> port.find(r.getInstructionId())).orElse(null);
        Instant now = Instant.now(clock);
        String status = pix == null ? "NOT_FOUND" : pix.status();
        switch (status) {
            case "PENDING" -> {
                return; // o PSP ainda não registrou a cobrança: a ativação gera novo agendamento
            }
            case "ACTIVE" -> {
                if (!pix.expiresAt().isAfter(now)) {
                    giveUp(r, actor, settings, "INSTRUCTION_EXPIRED", now);
                    return;
                }
                deliver(r, pix, actor, settings, now);
            }
            case "PAID" -> {
                r.notDelivered("ALREADY_PAID", null);
                if (r.paid(pix.paidAt() == null ? now : pix.paidAt())) {
                    recorder.paid(r);
                }
                requests.save(r);
                recorder.delivery(r, null, null);
            }
            default -> giveUp(r, actor, settings, "INSTRUCTION_" + status, now);
        }
    }

    private void deliver(PixRequest r, PixInstruction pix, Actor actor, PolicySettings settings, Instant now) {
        PixMessage.Result message = PixMessage.build(new PixMessage.Content(PixMessage.Kind.valueOf(r.getInstallmentKind()),
                r.getInstallmentNumber(), r.getAmount(), pix.amount(), pix.expiresAt(), pix.copyPaste()),
                TimeConfig.BUSINESS_ZONE);
        if (!message.approved()) {
            LOG.atWarn().addKeyValue("pixRequestId", r.getId()).addKeyValue("code", message.errorCode())
                    .addKeyValue("violations", String.join(",", message.violations()))
                    .log("Mensagem do Pix do assistente recusada antes do envio");
            if (!r.isReusedInstruction()) {
                cancelQuietly(actor, r.getInstructionId(), "Código Pix recusado pela validação do assistente");
            }
            giveUp(r, actor, settings, message.errorCode(), now);
            return;
        }
        MessageView sent;
        try {
            sent = actors.as(actor, () -> messages.send(new SendMessageCommand(r.getConversationId(), null, null, null,
                    null, null, null, null, message.text(), null, "assistant:pix:" + r.getId())));
        } catch (DomainException e) {
            if ("conversation.not_with_assistant".equals(e.code()) || "conversation.closed".equals(e.code())) {
                r.notDelivered("CONVERSATION_LEFT_ASSISTANT", null);
                requests.save(r);
                recorder.delivery(r, null, null);
                return;
            }
            throw e;
        }
        if ("FAILED".equals(sent.status())) {
            r.notDelivered(sent.failureCode() == null ? "SEND_FAILED" : sent.failureCode(), sent.id());
        } else {
            r.delivered(sent.id(), now);
        }
        requests.save(r);
        recorder.delivery(r, sent.channel(), r.getDeliveredAt());
    }

    /**
     * O devedor esperava o código: registra a não entrega, avisa com a mensagem padrão da política e
     * transfere para um atendente concluir o pagamento (se a conversa ainda estiver com o assistente).
     */
    private void giveUp(PixRequest r, Actor actor, PolicySettings settings, String code, Instant now) {
        r.notDelivered(code, null);
        requests.save(r);
        recorder.delivery(r, null, null);
        try {
            actors.as(actor, () -> messages.send(new SendMessageCommand(r.getConversationId(), null, null, null, null,
                    null, null, null, settings.fallbackMessage(), null, "assistant:pix:" + r.getId() + ":fallback")));
            actors.as(actor, () -> conversations.handoffToHumans(r.getConversationId(), new HandoffCommand(
                    HandoffReason.PAYMENT_ASSISTANCE.name(), "Transferência do assistente virtual: o Pix da parcela "
                    + r.getInstallmentNumber() + " (R$ " + r.getAmount().toPlainString() + ") não foi entregue ("
                    + code + "). Gere a cobrança pela tela do acordo.",
                    settings.requiredProfileFor(HandoffReason.PAYMENT_ASSISTANCE), "pix_request", r.getId())));
            sessions.findByConversationId(r.getConversationId()).ifPresent(s -> {
                if (s.getStatus() == SessionStatus.ACTIVE) {
                    s.handedOff(HandoffReason.PAYMENT_ASSISTANCE, now);
                    sessions.save(s);
                }
            });
        } catch (DomainException e) {
            LOG.atInfo().addKeyValue("pixRequestId", r.getId()).addKeyValue("code", e.code())
                    .log("Pix do assistente não entregue; conversa já não está com o assistente");
        }
    }

    private void cancelQuietly(Actor actor, UUID instructionId, String reason) {
        try {
            actors.as(actor, () -> {
                payments.getObject().cancel(instructionId, reason);
                return null;
            });
        } catch (DomainException e) {
            LOG.atWarn().addKeyValue("instructionId", instructionId).addKeyValue("code", e.code())
                    .log("Instrução do Pix do assistente não cancelada");
        }
    }
}
