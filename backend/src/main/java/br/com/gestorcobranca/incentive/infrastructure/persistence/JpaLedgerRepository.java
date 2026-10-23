package br.com.gestorcobranca.incentive.infrastructure.persistence;

import br.com.gestorcobranca.incentive.domain.model.attribution.BeneficiaryType;
import br.com.gestorcobranca.incentive.domain.model.ledger.CommissionLedgerEntry;
import br.com.gestorcobranca.incentive.domain.model.ledger.CommissionPeriod;
import br.com.gestorcobranca.incentive.domain.model.ledger.PeriodStatus;
import br.com.gestorcobranca.incentive.domain.repository.LedgerRepository;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
class JpaLedgerRepository implements LedgerRepository {

    private static final Set<UUID> NO_FILTER = Set.of(new UUID(0, 0));

    private final LedgerEntryJpa entries;
    private final CommissionPeriodJpa periods;

    JpaLedgerRepository(LedgerEntryJpa entries, CommissionPeriodJpa periods) {
        this.entries = entries;
        this.periods = periods;
    }

    @Override
    public CommissionLedgerEntry save(CommissionLedgerEntry entry) {
        return entries.save(entry);
    }

    @Override
    public Optional<CommissionLedgerEntry> findById(UUID id) {
        return entries.findById(id);
    }

    @Override
    public List<CommissionLedgerEntry> findByPayment(UUID paymentId) {
        return entries.findByPaymentIdOrderByCreatedAtAsc(paymentId);
    }

    @Override
    public List<CommissionLedgerEntry> findPendingDue(LocalDate until, int limit) {
        return entries.findPendingDue(until, Limit.of(limit));
    }

    @Override
    public List<CommissionLedgerEntry> findCleared(LocalDate periodStart) {
        return entries.findCleared(periodStart);
    }

    @Override
    public List<CommissionLedgerEntry> findCleared(LocalDate periodStart, BeneficiaryType type, UUID beneficiaryId) {
        return entries.findCleared(periodStart, type, beneficiaryId);
    }

    @Override
    public List<CommissionLedgerEntry> findPending(BeneficiaryType type, UUID beneficiaryId) {
        return entries.findPending(type, beneficiaryId);
    }

    @Override
    public Page<CommissionLedgerEntry> findByBeneficiary(BeneficiaryType type, UUID beneficiaryId, LocalDate periodStart,
                                                         Collection<UUID> creditorIds, Pageable pageable) {
        boolean all = creditorIds == null || creditorIds.isEmpty();
        Collection<UUID> filter = all ? NO_FILTER : creditorIds;
        return periodStart == null ? entries.findByBeneficiary(type, beneficiaryId, all, filter, pageable)
                : entries.findByBeneficiaryInPeriod(type, beneficiaryId, periodStart, all, filter, pageable);
    }

    @Override
    public List<CommissionLedgerEntry> findByStatement(UUID statementId) {
        return entries.findByStatementIdOrderByPaidOnAsc(statementId);
    }

    @Override
    public CommissionPeriod savePeriod(CommissionPeriod period) {
        return periods.save(period);
    }

    @Override
    public Optional<CommissionPeriod> findPeriod(LocalDate periodStart) {
        return periods.findByPeriodStart(periodStart);
    }

    @Override
    public Optional<CommissionPeriod> findPeriodById(UUID periodId) {
        return periods.findById(periodId);
    }

    @Override
    public Optional<CommissionPeriod> findPeriodForUpdate(LocalDate periodStart) {
        return periods.findForUpdate(periodStart);
    }

    @Override
    public List<CommissionPeriod> findPeriods() {
        return periods.findAllByOrderByPeriodStartDesc();
    }

    @Override
    public Optional<CommissionPeriod> findLatestClosedPeriod() {
        return periods.findFirstByStatusOrderByPeriodStartDesc(PeriodStatus.CLOSED);
    }
}
