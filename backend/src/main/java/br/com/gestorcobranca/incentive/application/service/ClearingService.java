package br.com.gestorcobranca.incentive.application.service;

import br.com.gestorcobranca.incentive.domain.model.ledger.CommissionLedgerEntry;
import br.com.gestorcobranca.incentive.domain.model.ledger.EntryKind;
import br.com.gestorcobranca.incentive.domain.model.ledger.EntryStatus;
import br.com.gestorcobranca.incentive.domain.repository.LedgerRepository;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Libera os lançamentos cuja carência terminou (§5.4): créditos vão para o período de
 * competência; compensações acompanham o crédito que anulam (ou, com o período dele fechado,
 * seguem a regra de estorno do plano).
 */
@Service
@Transactional
class ClearingService {

    private static final int BATCH = 500;

    private final LedgerRepository ledger;
    private final PeriodCalendar periods;
    private final PlanResolver planResolver;
    private final BusinessEventLogger businessEvents;
    private final IncentiveMetrics metrics;
    private final Clock clock;

    ClearingService(LedgerRepository ledger, PeriodCalendar periods, PlanResolver planResolver,
                    BusinessEventLogger businessEvents, IncentiveMetrics metrics, Clock clock) {
        this.ledger = ledger;
        this.periods = periods;
        this.planResolver = planResolver;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** @return lançamentos processados (liberados ou dispensados) */
    int clearDue(LocalDate today) {
        int processed = 0;
        List<CommissionLedgerEntry> due;
        do {
            due = ledger.findPendingDue(today, BATCH);
            int batch = 0;
            for (CommissionLedgerEntry entry : due) {
                if (process(entry)) {
                    batch++;
                }
            }
            processed += batch;
            if (batch == 0) {
                break;
            }
        } while (due.size() == BATCH);
        return processed;
    }

    private boolean process(CommissionLedgerEntry entry) {
        Instant now = Instant.now(clock);
        if (entry.getKind() == EntryKind.ACCRUAL) {
            entry.clear(periods.firstOpenFrom(entry.getPaidOn()), now);
        } else {
            CommissionLedgerEntry original = ledger.findById(entry.getReversesEntryId()).orElseThrow();
            if (original.getStatus() == EntryStatus.PENDING) {
                return false;
            }
            if (periods.isOpen(original.getPeriodStart())) {
                entry.clear(original.getPeriodStart(), now);
            } else if (planResolver.byId(original.getPlanId()).map(p -> p.rules().clearing().clawbackEnabled()).orElse(false)) {
                entry.clear(periods.firstOpenFrom(entry.getConfirmedOn()), now);
            } else {
                entry.waive();
            }
        }
        ledger.save(entry);
        metrics.ledgerTransition(entry.getStatus().name());
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.COMMISSION_ENTRY_CLEARED)
                .creditor(entry.getCreditorId()).portfolio(entry.getPortfolioId())
                .reference("commission_entry", entry.getId()).channel(entry.getChannel())
                .attr("kind", entry.getKind()).attr("status", entry.getStatus())
                .attr("beneficiaryType", entry.getBeneficiaryType()).attr("beneficiaryId", entry.getBeneficiaryId())
                .attr("origin", entry.getOrigin())
                .attr("period", entry.getPeriodStart() == null ? null : entry.getPeriodStart().toString()));
        return true;
    }
}
