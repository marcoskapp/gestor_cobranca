package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.OpenDisputeCommand;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases.FollowUpInput;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases.FollowUpResult;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases.RecordInteractionCommand;
import br.com.gestorcobranca.collection.domain.model.AssignmentMode;
import br.com.gestorcobranca.collection.domain.model.CollectionCase;
import br.com.gestorcobranca.collection.domain.model.Dispute;
import br.com.gestorcobranca.collection.domain.model.DisputeOrigin;
import br.com.gestorcobranca.collection.domain.model.DisputeType;
import br.com.gestorcobranca.collection.domain.model.Interaction;
import br.com.gestorcobranca.collection.domain.model.Tabulation;
import br.com.gestorcobranca.collection.domain.model.TabulationFollowUp;
import br.com.gestorcobranca.compliance.application.port.in.ContactRestrictionUseCases;
import br.com.gestorcobranca.compliance.application.port.in.ContactRestrictionUseCases.RestrictionView;
import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases.ContactView;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Ação automática da tabulação ({@link TabulationFollowUp}), executada por {@code InteractionService}
 * na mesma transação do registro da interação: se a ação falha, nada é registrado. O fato relatado
 * pelo operador produz o efeito devido sem depender de um segundo passo manual:
 * <ul>
 *   <li>{@code OPEN_DISPUTE} — contestação de origem OPERATOR com os títulos em cobrança do caso (ou os
 *       informados), suspendendo a cobrança e o contato; já havendo contestação em aberto no caso, nada
 *       novo é aberto ({@code ALREADY_IN_EFFECT});</li>
 *   <li>{@code REPORT_DECEASED} — restrição de contato {@code DECEASED} do devedor em todos os canais e
 *       credores (compliance), vinculada à interação;</li>
 *   <li>{@code INVALIDATE_CONTACT} — o contato usado na interação passa a inválido (debt).</li>
 * </ul>
 * Depois do falecimento a elegibilidade do caso é reavaliada no ato (os demais casos do devedor reagem
 * ao {@code ContactRestrictionChanged}). Cada execução registra auditoria,
 * evento de negócio {@code interaction.follow_up} e a métrica {@code collection.tabulation_follow_ups}.
 */
@Component
class TabulationFollowUps {

    static final String DONE = "DONE";
    static final String ALREADY_IN_EFFECT = "ALREADY_IN_EFFECT";
    static final String NO_TITLES = "NO_TITLES";

    private static final String INVALID_CONTACT_REASON =
            "Contato informado como errado (não pertence ao devedor) em atendimento de cobrança";

    private final DisputeService disputes;
    private final ContactRestrictionUseCases restrictions;
    private final DebtorContactUseCases contacts;
    private final CaseSynchronizer synchronizer;
    private final CaseRecorder caseRecorder;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final CollectionMetrics metrics;
    private final Clock clock;

    TabulationFollowUps(DisputeService disputes, ContactRestrictionUseCases restrictions, DebtorContactUseCases contacts,
                        CaseSynchronizer synchronizer, CaseRecorder caseRecorder, AuditTrail auditTrail,
                        BusinessEventLogger businessEvents, CollectionMetrics metrics, Clock clock) {
        this.disputes = disputes;
        this.restrictions = restrictions;
        this.contacts = contacts;
        this.synchronizer = synchronizer;
        this.caseRecorder = caseRecorder;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** Ação validada antes de gravar a interação (os dados exigidos pela ação). */
    record Plan(TabulationFollowUp action, DisputeType disputeType, FollowUpInput input) {
    }

    /** Valida os dados da ação contra a tabulação; nada é gravado. */
    static Plan plan(Tabulation t, RecordInteractionCommand cmd) {
        FollowUpInput input = cmd.followUp();
        TabulationFollowUp action = t.getFollowUp();
        if (action.isNone()) {
            Guard.require(input == null, "interaction.follow_up_not_allowed",
                    "A tabulação %s não tem ação automática".formatted(t.getCode()));
            return new Plan(action, null, null);
        }
        if (action != TabulationFollowUp.OPEN_DISPUTE) {
            Guard.require(input == null || (input.disputeType() == null && input.titleIds().isEmpty()),
                    "interaction.follow_up_not_allowed",
                    "A tabulação %s não abre contestação".formatted(t.getCode()));
        }
        return switch (action) {
            case OPEN_DISPUTE -> {
                DisputeType informed = CollectionEnums.parseOptional(DisputeType.class,
                        input == null ? null : input.disputeType(), "dispute.type_invalid", "Tipo da contestação");
                DisputeType type = informed != null ? informed : t.getDisputeType();
                Guard.require(type != null, "interaction.dispute_type_required",
                        "A tabulação %s abre contestação: informe o tipo".formatted(t.getCode()));
                yield new Plan(action, type, input);
            }
            case INVALIDATE_CONTACT -> {
                Guard.require(cmd.contactId() != null, "interaction.contact_required",
                        "A tabulação %s invalida o contato usado: informe o contato".formatted(t.getCode()));
                yield new Plan(action, null, null);
            }
            default -> new Plan(action, null, null);
        };
    }

    /** Executa a ação planejada. Chamado depois de gravar a interação e o caso. */
    Optional<FollowUpResult> execute(CollectionCase c, Interaction interaction, Plan plan) {
        if (plan.action().isNone()) {
            return Optional.empty();
        }
        FollowUpResult result = switch (plan.action()) {
            case OPEN_DISPUTE -> openDispute(c, interaction, plan);
            case REPORT_DECEASED -> reportDeceased(c, interaction);
            case INVALIDATE_CONTACT -> invalidateContact(c, interaction);
            case NONE -> throw new IllegalStateException("sem ação");
        };
        record(c, interaction, result);
        return Optional.of(result);
    }

    private FollowUpResult openDispute(CollectionCase c, Interaction interaction, Plan plan) {
        String action = plan.action().name();
        Optional<Dispute> open = disputes.openOn(c.getId());
        if (open.isPresent()) {
            return new FollowUpResult(action, ALREADY_IN_EFFECT, "dispute", open.get().getId());
        }
        if (!disputes.hasTitlesToDispute(c)) {
            return new FollowUpResult(action, NO_TITLES, null, null);
        }
        Set<UUID> titleIds = plan.input() == null ? Set.of() : plan.input().titleIds();
        Dispute dispute = disputes.openFor(c, new OpenDisputeCommand(titleIds, plan.disputeType().name(),
                DisputeOrigin.OPERATOR.name(), interaction.getNote(), true, null), interaction.getId());
        return new FollowUpResult(action, DONE, "dispute", dispute.getId());
    }

    private FollowUpResult reportDeceased(CollectionCase c, Interaction interaction) {
        RestrictionView restriction = restrictions.registerDeceasedReport(c.getDebtorId(), interaction.getId());
        boolean created = interaction.getId().equals(restriction.originInteractionId());
        if (created) {
            refreshEligibility(c, "Falecimento informado no atendimento");
        }
        return new FollowUpResult(TabulationFollowUp.REPORT_DECEASED.name(), created ? DONE : ALREADY_IN_EFFECT,
                "contact_restriction", restriction.id());
    }

    private FollowUpResult invalidateContact(CollectionCase c, Interaction interaction) {
        UUID contactId = interaction.getContactId();
        ContactView current = contacts.list(c.getDebtorId()).stream().filter(v -> v.id().equals(contactId)).findFirst()
                .orElseThrow(() -> ResourceNotFoundException.of("debtor_contact", contactId));
        String action = TabulationFollowUp.INVALIDATE_CONTACT.name();
        if ("INVALID".equals(current.status())) {
            return new FollowUpResult(action, ALREADY_IN_EFFECT, "debtor_contact", contactId);
        }
        // a elegibilidade do caso é por restrição de canal, não pela existência de contato: nada a reavaliar; o
        // discador e os envios deixam de usar o contato (só contatos não inválidos são entregues a eles)
        contacts.markInvalid(c.getDebtorId(), contactId, INVALID_CONTACT_REASON);
        return new FollowUpResult(action, DONE, "debtor_contact", contactId);
    }

    /** Reavalia a elegibilidade e a fila do caso com a restrição ou o contato que acabou de mudar. */
    private void refreshEligibility(CollectionCase c, String reason) {
        CaseRecorder.Snapshot before = CaseRecorder.snapshot(c);
        synchronizer.applyEligibilityAndRoute(c, synchronizer.context());
        caseRecorder.commit(c, before, AssignmentMode.ROUTING, reason, "tabulation_follow_up");
    }

    private void record(CollectionCase c, Interaction interaction, FollowUpResult result) {
        auditTrail.record(AuditEntry.of("interaction.follow_up_executed", "interaction", interaction.getId())
                .creditor(c.getCreditorId())
                .created("action", result.action())
                .created("outcome", result.outcome())
                .created("referenceType", result.referenceType())
                .created("referenceId", result.referenceId())
                .meta("caseId", c.getId())
                .meta("outcomeCode", interaction.getOutcomeCode()));
        var event = CaseRecorder.base(BusinessEventTypes.INTERACTION_FOLLOW_UP, c, CollectionViews.today(clock))
                .reference("interaction", interaction.getId())
                .channel(interaction.getChannel().name())
                .attr("action", result.action())
                .attr("outcome", result.outcome())
                .attr("outcomeCode", interaction.getOutcomeCode())
                .attr("source", interaction.getSource())
                .attr("operatorId", interaction.getOperatorId());
        if (result.referenceId() != null) {
            event.attr("referenceType", result.referenceType()).attr("referenceId", result.referenceId());
        }
        businessEvents.log(event);
        metrics.followUp(result.action(), result.outcome());
    }
}
