package br.com.gestorcobranca.campaign.application.service;

import br.com.gestorcobranca.campaign.application.port.out.CampaignOfferPort;
import br.com.gestorcobranca.campaign.application.port.out.CampaignOfferPort.OfferRequest;
import br.com.gestorcobranca.campaign.application.port.out.CampaignOfferPort.OfferResult;
import br.com.gestorcobranca.campaign.domain.model.ActionType;
import br.com.gestorcobranca.campaign.domain.model.AudienceSnapshot;
import br.com.gestorcobranca.campaign.domain.model.CadenceStep;
import br.com.gestorcobranca.campaign.domain.model.Enrollment;
import br.com.gestorcobranca.campaign.domain.model.SkipReason;
import br.com.gestorcobranca.campaign.domain.model.StepAction;
import br.com.gestorcobranca.collection.application.port.in.CaseWorkUseCases;
import br.com.gestorcobranca.collection.application.port.in.CaseWorkUseCases.AssignCommand;
import br.com.gestorcobranca.collection.application.port.in.TaskUseCases;
import br.com.gestorcobranca.collection.application.port.in.TaskUseCases.ScheduleTaskCommand;
import br.com.gestorcobranca.communication.application.port.in.MessageUseCases;
import br.com.gestorcobranca.communication.application.port.in.MessageUseCases.MessageView;
import br.com.gestorcobranca.communication.application.port.in.MessageUseCases.SendMessageCommand;
import br.com.gestorcobranca.compliance.application.port.in.ContactGate;
import br.com.gestorcobranca.compliance.application.port.in.ContactGate.ContactDecision;
import br.com.gestorcobranca.compliance.application.port.in.ContactGate.ContactRequest;
import br.com.gestorcobranca.compliance.application.port.in.ContactGate.DenialReason;
import br.com.gestorcobranca.portal.application.port.in.PortalLinkUseCases;
import br.com.gestorcobranca.portal.application.port.in.PortalLinkUseCases.DispatchCommand;
import br.com.gestorcobranca.portal.application.port.in.PortalLinkUseCases.IssueLinkCommand;
import br.com.gestorcobranca.portal.application.port.in.PortalLinkUseCases.LinkIssue;
import br.com.gestorcobranca.shared.config.TimeConfig;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Executa a ação de uma etapa pelos casos de uso públicos dos outros módulos, como ator de sistema:
 * mensagem pelo communication (que passa pelo {@code ContactGate} na verificação prévia e
 * imediatamente antes do envio), tarefa/retorno e dono do atendimento pelo collection, oferta pela
 * porta {@link CampaignOfferPort}, próxima parcela do acordo pelo negotiation ({@link AgreementInstallments})
 * e link de uso único pelo portal ({@link PortalLinkUseCases}, gerado imediatamente antes do envio, na
 * mesma transação: se ela for desfeita, o link também é). Antes de pedir a mensagem, a campanha consulta o porteiro
 * ({@code evaluate}, sem registrar tentativa) para adiar negações temporárias (janela, feriado,
 * limites, intervalo) em vez de gastar a etapa.
 */
@Component
class ActionExecutor {

    /** Negações que passam com o tempo: a etapa é adiada até o próximo horário permitido. */
    /** Origem dos links do portal pedidos pela régua (log de acesso e auditoria do portal). */
    static final String PORTAL_LINK_ORIGIN = "CAMPAIGN";

    static final Set<DenialReason> TEMPORAL = EnumSet.of(DenialReason.OUTSIDE_WINDOW, DenialReason.HOLIDAY,
            DenialReason.DAILY_LIMIT, DenialReason.WEEKLY_LIMIT, DenialReason.MIN_INTERVAL);

    private final ContactGate gate;
    private final MessageUseCases messages;
    private final TaskUseCases tasks;
    private final CaseWorkUseCases caseWork;
    private final VariableResolver variables;
    private final ObjectProvider<CampaignOfferPort> offers;
    private final AgreementInstallments installments;
    private final PortalLinkUseCases portalLinks;

    ActionExecutor(ContactGate gate, MessageUseCases messages, TaskUseCases tasks, CaseWorkUseCases caseWork,
                   VariableResolver variables, ObjectProvider<CampaignOfferPort> offers,
                   AgreementInstallments installments, PortalLinkUseCases portalLinks) {
        this.gate = gate;
        this.messages = messages;
        this.tasks = tasks;
        this.caseWork = caseWork;
        this.variables = variables;
        this.offers = offers;
        this.installments = installments;
        this.portalLinks = portalLinks;
    }

    sealed interface Result permits Executed, Skipped, Deferred {
    }

    record Executed(UUID messageId, String messageStatus, UUID taskId) implements Result {
    }

    /** @param terminalApplied a etapa terminal pode encerrar a inscrição (o estado desejado já vale) */
    record Skipped(String reason, String detail, UUID messageId, boolean terminalApplied) implements Result {
    }

    /** Negação temporária do porteiro: próxima tentativa a partir de {@code until}. */
    record Deferred(Instant until, String detail) implements Result {
    }

    Result execute(Enrollment e, CadenceStep step, AudienceSnapshot snapshot, String idempotencyKey, Instant now) {
        StepAction action = step.action();
        String reason = "Régua da campanha, etapa " + step.code();
        return switch (action.type()) {
            case SEND_MESSAGE, OFFER -> message(e, action, snapshot, idempotencyKey, now);
            case CREATE_TASK -> task(e, action, now);
            case HAND_TO_ASSISTANT, MOVE_TO_QUEUE -> caseAction(action.type(), action.teamId(), e, snapshot, reason);
        };
    }

    /** Entregar ao assistente ou mover para a fila (etapa terminal ou transição por resposta). */
    Result caseAction(ActionType type, UUID teamId, Enrollment e, AudienceSnapshot snapshot, String reason) {
        if (type == ActionType.HAND_TO_ASSISTANT) {
            if ("ASSISTANT".equals(snapshot.ownerType())) {
                return new Skipped(SkipReason.NOT_APPLICABLE, "already_with_assistant", null, true);
            }
            caseWork.handToAssistant(e.getCaseId(), reason);
            return new Executed(null, null, null);
        }
        if (teamId != null) {
            caseWork.assign(e.getCaseId(), new AssignCommand(null, teamId, reason));
            return new Executed(null, null, null);
        }
        if ("USER".equals(snapshot.ownerType()) || "ASSISTANT".equals(snapshot.ownerType())) {
            caseWork.release(e.getCaseId(), reason);
            return new Executed(null, null, null);
        }
        return new Skipped(SkipReason.NOT_APPLICABLE, "already_in_queue", null, true);
    }

    private Result message(Enrollment e, StepAction action, AudienceSnapshot snapshot, String idempotencyKey,
                           Instant now) {
        ContactDecision decision = gate.evaluate(new ContactRequest(e.getDebtorId(), e.getCreditorId(),
                action.channel(), null, now, "CAMPAIGN", null, null));
        if (!decision.allowed()) {
            String reasons = decision.reasons().stream().map(Enum::name).collect(Collectors.joining(","));
            if (decision.nextAllowedAt() != null && TEMPORAL.containsAll(decision.reasons())) {
                return new Deferred(decision.nextAllowedAt(), reasons);
            }
            return new Skipped(SkipReason.CONTACT_DENIED, reasons, null, false);
        }
        OfferResult offer = null;
        if (action.type() == ActionType.OFFER) {
            CampaignOfferPort port = offers.getIfAvailable();
            if (port == null) {
                return new Skipped(SkipReason.OFFER_UNAVAILABLE, "no_offer_adapter", null, false);
            }
            offer = port.createOffer(new OfferRequest(e.getId(), e.getCaseId(), e.getDebtorId(), e.getCreditorId(),
                    e.getPortfolioId(), action.offer(), action.channel(), idempotencyKey));
            if (!offer.available()) {
                return new Skipped(SkipReason.OFFER_UNAVAILABLE, offer.reason(), null, false);
            }
        }
        AgreementInstallments.NextInstallment installment = null;
        if (action.needsInstallment()) {
            AgreementInstallments.Lookup lookup = installments.next(e.getCaseId(), e.getDebtorId(), e.getCreditorId(),
                    e.getPortfolioId());
            if (!lookup.available()) {
                return new Skipped(lookup.unavailableReason(), lookup.unavailableReason().toLowerCase(Locale.ROOT),
                        null, false);
            }
            installment = lookup.installment();
        }
        // o link é gerado por último, imediatamente antes do envio: nenhuma outra verificação o descarta
        LinkIssue link = null;
        if (action.needsPortalLink()) {
            link = portalLinks.issue(new IssueLinkCommand(e.getDebtorId(), e.getCreditorId(), e.getPortfolioId(),
                    action.channel(), PORTAL_LINK_ORIGIN, reference(e)));
            if (!link.available()) {
                return new Skipped(SkipReason.PORTAL_UNAVAILABLE, link.unavailableReason(), null, false);
            }
        }
        Map<String, String> values = variables.resolve(action.variables(), e.getDebtorId(), snapshot,
                new VariableResolver.Inputs(offer, installment, link), now);
        MessageView sent = messages.send(new SendMessageCommand(null, e.getDebtorId(), e.getCreditorId(), e.getCaseId(),
                action.channel(), null, action.templateCode(), values, null, e.getId(), idempotencyKey));
        if (link != null) {
            // mensagem negada pelo porteiro ou recusada: o portal revoga o link na hora
            portalLinks.recordDispatch(new DispatchCommand(link.linkId(), sent.id(), sent.status(), PORTAL_LINK_ORIGIN,
                    reference(e)));
        }
        if ("FAILED".equals(sent.status()) && "CONTACT_DENIED".equals(sent.failureCode())) {
            return new Skipped(SkipReason.CONTACT_DENIED, sent.failureDetail(), sent.id(), false);
        }
        return new Executed(sent.id(), sent.status(), null);
    }

    private static String reference(Enrollment e) {
        return "campaign-enrollment:" + e.getId();
    }

    private Result task(Enrollment e, StepAction action, Instant now) {
        LocalDateTime due = LocalDateTime.ofInstant(now.plus(Duration.ofMinutes(action.taskDueInMinutes())),
                TimeConfig.BUSINESS_ZONE);
        var task = tasks.schedule(e.getCaseId(), new ScheduleTaskCommand(action.taskType(), due, null, action.taskNote()));
        return new Executed(null, null, task.id());
    }
}
