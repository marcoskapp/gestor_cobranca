package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.domain.model.Dispute;
import br.com.gestorcobranca.collection.domain.model.DisputeAdjustmentAction;
import br.com.gestorcobranca.collection.domain.model.DisputeAdjustmentStatus;
import br.com.gestorcobranca.collection.domain.model.DisputeOutcome;
import br.com.gestorcobranca.collection.domain.repository.DisputeRepository;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases.TitleView;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Ajuste no título a partir da resolução (sem efeito financeiro automático): procedente ou parcialmente procedente
 * sugere cancelar os títulos, corrigir o valor ou baixar por pagamento comprovado; a ação é feita nos fluxos
 * existentes do debt e do payment, com as regras deles (motivo, permissão, evidência e revisão por segunda pessoa do
 * pagamento externo). A sugestão fica pendente até o efeito aparecer no título — detectado no consumo de
 * {@code TitleStatusChanged} e na varredura diária — ou até ser dispensada com motivo.
 * <ul>
 *   <li>cancelar: nenhum título da contestação continua em cobrança (cancelado, devolvido ou quitado);</li>
 *   <li>corrigir valor ou baixar por pagamento: o saldo em aberto dos títulos ficou abaixo do saldo da resolução.</li>
 * </ul>
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class DisputeAdjustments {

    private static final Set<String> IN_COLLECTION = Set.of("OPEN", "SUSPENDED");

    private final DisputeRepository disputes;
    private final DebtTitleUseCases titles;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final CollectionMetrics metrics;
    private final Clock clock;

    DisputeAdjustments(DisputeRepository disputes, DebtTitleUseCases titles, CurrentActor currentActor,
                       AuditTrail auditTrail, BusinessEventLogger businessEvents, CollectionMetrics metrics, Clock clock) {
        this.disputes = disputes;
        this.titles = titles;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Ação a sugerir na resolução.
     *
     * @param requested nula = a sugerida pelo tipo; {@code NONE} = nenhuma
     */
    static DisputeAdjustmentAction actionFor(Dispute d, DisputeOutcome outcome, String requested) {
        if (requested == null || requested.isBlank()) {
            return DisputeAdjustmentAction.suggestedFor(d.getType(), outcome);
        }
        if ("NONE".equals(requested.trim().toUpperCase(Locale.ROOT))) {
            return null;
        }
        return CollectionEnums.parse(DisputeAdjustmentAction.class, requested, "dispute.adjustment_invalid",
                "Ajuste no título");
    }

    /** Saldo em aberto atual dos títulos da contestação (referência para detectar o ajuste). */
    Money openBalance(Dispute d) {
        Map<UUID, TitleView> byId = titlesOf(d);
        return d.getTitleIds().stream().map(byId::get).filter(java.util.Objects::nonNull)
                .map(t -> Money.of(t.openBalance())).reduce(Money.ZERO, Money::plus);
    }

    void suggested(Dispute d) {
        if (d.getAdjustmentStatus() != DisputeAdjustmentStatus.PENDING) {
            return;
        }
        auditTrail.record(AuditEntry.of("dispute.adjustment_suggested", "dispute", d.getId())
                .creditor(d.getCreditorId())
                .created("action", d.getAdjustmentAction())
                .created("baseline", d.getAdjustmentBaseline()));
        businessEvents.log(DisputeEvents.base(BusinessEventTypes.DISPUTE_ADJUSTMENT_SUGGESTED, d)
                .amount(d.getAdjustmentBaseline())
                .attr("action", d.getAdjustmentAction())
                .attr("outcome", d.getOutcome()));
        metrics.disputeAdjustment(d.getAdjustmentAction().name(), DisputeAdjustmentStatus.PENDING.name());
    }

    Dispute dismiss(Dispute d, String reason) {
        if (!d.closeAdjustment(DisputeAdjustmentStatus.DISMISSED, reason, currentActor.actorId(), Instant.now(clock))) {
            throw new br.com.gestorcobranca.shared.domain.ConflictException("dispute.adjustment_not_pending",
                    "Não há ajuste pendente nesta contestação");
        }
        Dispute saved = disputes.save(d);
        closed(saved, "manual");
        return saved;
    }

    /** Ajustes pendentes do devedor cujo efeito já aparece nos títulos passam a feitos. */
    int reconcile(UUID debtorId) {
        List<Dispute> pending = disputes.findPendingAdjustmentsByDebtorId(debtorId);
        if (pending.isEmpty()) {
            return 0;
        }
        Map<UUID, TitleView> byId = titles.listByDebtor(debtorId).stream()
                .collect(Collectors.toMap(TitleView::id, Function.identity()));
        int done = 0;
        for (Dispute d : pending) {
            if (effectVisible(d, byId) && d.closeAdjustment(DisputeAdjustmentStatus.DONE, null, null, Instant.now(clock))) {
                closed(disputes.save(d), "detected");
                done++;
            }
        }
        return done;
    }

    /** Varredura diária dos pendentes (rede de segurança do consumo de eventos). */
    int sweep(int limit) {
        int done = 0;
        for (UUID debtorId : disputes.findPendingAdjustments(limit).stream().map(Dispute::getDebtorId).distinct().toList()) {
            done += reconcile(debtorId);
        }
        return done;
    }

    static boolean effectVisible(Dispute d, Map<UUID, TitleView> titlesById) {
        List<TitleView> list = d.getTitleIds().stream().map(titlesById::get).filter(java.util.Objects::nonNull).toList();
        if (list.isEmpty()) {
            return false;
        }
        boolean noneInCollection = list.stream().noneMatch(t -> IN_COLLECTION.contains(t.status())
                && t.openBalance() != null && t.openBalance().signum() > 0);
        if (d.getAdjustmentAction() == DisputeAdjustmentAction.CANCEL_TITLES) {
            return noneInCollection;
        }
        Money current = list.stream().map(t -> Money.of(t.openBalance())).reduce(Money.ZERO, Money::plus);
        return noneInCollection || current.isLessThan(d.getAdjustmentBaseline());
    }

    private void closed(Dispute d, String trigger) {
        auditTrail.record(AuditEntry.of("dispute.adjustment_closed", "dispute", d.getId())
                .creditor(d.getCreditorId())
                .reason(d.getAdjustmentNote())
                .change("adjustmentStatus", DisputeAdjustmentStatus.PENDING, d.getAdjustmentStatus())
                .meta("trigger", trigger));
        businessEvents.log(DisputeEvents.base(BusinessEventTypes.DISPUTE_ADJUSTMENT_CLOSED, d)
                .attr("action", d.getAdjustmentAction())
                .attr("status", d.getAdjustmentStatus())
                .attr("trigger", trigger)
                .attr("daysAfterResolution", d.getResolvedAt() == null ? null
                        : Duration.between(d.getResolvedAt(), d.getAdjustmentClosedAt()).toDays()));
        metrics.disputeAdjustment(d.getAdjustmentAction().name(), d.getAdjustmentStatus().name());
    }

    private Map<UUID, TitleView> titlesOf(Dispute d) {
        return titles.listByDebtor(d.getDebtorId()).stream().collect(Collectors.toMap(TitleView::id, Function.identity()));
    }
}
