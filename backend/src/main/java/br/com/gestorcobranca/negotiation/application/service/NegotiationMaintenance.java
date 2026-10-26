package br.com.gestorcobranca.negotiation.application.service;

import br.com.gestorcobranca.negotiation.domain.model.agreement.Agreement;
import br.com.gestorcobranca.negotiation.domain.model.proposal.Proposal;
import br.com.gestorcobranca.negotiation.domain.repository.AgreementRepository;
import br.com.gestorcobranca.negotiation.domain.repository.ProposalRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Tarefas diárias do módulo, em lotes por tenant (cada lote numa transação, chamada pelo
 * {@code NegotiationJobs}): expiração de propostas e varredura de acordos em vigor (parcelas
 * vencidas → OVERDUE; regra de quebra da política → BROKEN).
 */
@Component
class NegotiationMaintenance {

    static final int BATCH = 200;

    private final ProposalRepository proposals;
    private final AgreementRepository agreements;
    private final AgreementRecorder recorder;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final NegotiationMetrics metrics;
    private final BreakSuspensionService breakSuspensions;
    private final Clock clock;

    NegotiationMaintenance(ProposalRepository proposals, AgreementRepository agreements, AgreementRecorder recorder,
                           AuditTrail auditTrail, BusinessEventLogger businessEvents, NegotiationMetrics metrics,
                           BreakSuspensionService breakSuspensions, Clock clock) {
        this.proposals = proposals;
        this.agreements = agreements;
        this.recorder = recorder;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.breakSuspensions = breakSuspensions;
        this.clock = clock;
    }

    /** Expira um lote de propostas em aberto fora da validade; devolve o tamanho do lote. */
    int expireProposals() {
        return expireProposals(today());
    }

    /** @param today dia de referência no fuso de operação (testes simulam dias futuros) */
    int expireProposals(LocalDate today) {
        Instant now = Instant.now(clock);
        List<Proposal> expired = proposals.findOpenExpiredBefore(today, BATCH);
        for (Proposal p : expired) {
            var previous = p.getStatus();
            p.expire(now);
            proposals.save(p);
            auditTrail.record(AuditEntry.of("proposal.expired", ProposalService.RESOURCE, p.getId())
                    .creditor(p.getCreditorId()).change("status", previous, p.getStatus()));
            businessEvents.log(BusinessEvent.of(BusinessEventTypes.PROPOSAL_EXPIRED).creditor(p.getCreditorId())
                    .portfolio(p.getPortfolioId()).debtor(p.getDebtorId()).collectionCase(p.getCaseId())
                    .reference(ProposalService.RESOURCE, p.getId()).channel(p.getChannel()).amount(p.getTotalAmount())
                    .attr("previousStatus", previous).attr("actorType", p.getActorType())
                    .attr("concessionLevel", p.getLevel()));
        }
        if (!expired.isEmpty()) {
            metrics.proposalsExpired(expired.size());
        }
        return expired.size();
    }

    /**
     * Confere um lote de suspensões da quebra em vigor (acordos com id após {@code afterId}): encerra as
     * que tiveram a revisão concluída, o prazo vencido ou o acordo encerrado — inclusive de acordos que não
     * entram na varredura de parcelas vencidas. Devolve o último id do lote (nulo = fim).
     */
    UUID reconcileBreakSuspensions(UUID afterId) {
        return reconcileBreakSuspensions(afterId, today());
    }

    /** @param today dia de referência no fuso de operação (testes simulam dias futuros) */
    UUID reconcileBreakSuspensions(UUID afterId, LocalDate today) {
        Instant now = Instant.now(clock);
        List<UUID> ids = breakSuspensions.activeAgreementIds(afterId, BATCH);
        for (UUID id : ids) {
            agreements.findByIdForUpdate(id).ifPresent(a -> breakSuspensions.reconcileLocked(a, today, now));
        }
        return ids.size() < BATCH ? null : ids.getLast();
    }

    /**
     * Varre um lote de acordos em vigor com parcela vencida (ids após {@code afterId}): marca as
     * parcelas em atraso e quebra os que atingiram a regra — salvo pagamento em revisão (suspensão),
     * carência após recusa ou pagamento confirmado em trânsito ({@link BreakSuspensionService#evaluate}).
     * Devolve o último id do lote (nulo = fim).
     */
    UUID sweepAgreements(UUID afterId) {
        return sweepAgreements(afterId, today());
    }

    /** @param today dia de referência no fuso de operação (testes simulam dias futuros) */
    UUID sweepAgreements(UUID afterId, LocalDate today) {
        Instant now = Instant.now(clock);
        List<UUID> ids = agreements.findActiveIdsWithInstallmentDueBefore(today, afterId, BATCH);
        for (UUID id : ids) {
            Agreement a = agreements.findByIdForUpdate(id).orElse(null);
            if (a == null || !a.getStatus().isActive()) {
                continue;
            }
            List<Agreement.InstallmentChange> overdue = a.refreshInstallments(today);
            Agreement.Ending ending = null;
            if (a.breakRuleMet(today) && breakSuspensions.evaluate(a, today, now).allowsBreak()) {
                ending = a.breakAgreement("Regra de quebra: " + a.overdueBeyondTolerance(today) + " parcela(s) em atraso há"
                        + " mais de " + a.getBreakToleranceDays() + " dia(s)", today, now);
            }
            agreements.save(a);
            overdue.forEach(c -> recorder.installmentChanged(a, c, now));
            if (ending != null) {
                recorder.statusChanged(a, ending.change(), now);
                ending.installmentChanges().forEach(c -> recorder.installmentChanged(a, c, now));
            }
        }
        return ids.size() < BATCH ? null : ids.getLast();
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
    }
}
