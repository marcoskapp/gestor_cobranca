package br.com.gestorcobranca.incentive.infrastructure.persistence;

import br.com.gestorcobranca.incentive.domain.model.attribution.BeneficiaryType;
import br.com.gestorcobranca.incentive.domain.model.ledger.CommissionLedgerEntry;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

interface LedgerEntryJpa extends JpaRepository<CommissionLedgerEntry, UUID> {

    List<CommissionLedgerEntry> findByPaymentIdOrderByCreatedAtAsc(UUID paymentId);

    @Query("""
            select e from CommissionLedgerEntry e
            where e.status = br.com.gestorcobranca.incentive.domain.model.ledger.EntryStatus.PENDING and e.clearsOn <= :until
            order by e.kind asc, e.clearsOn asc, e.createdAt asc
            """)
    List<CommissionLedgerEntry> findPendingDue(LocalDate until, Limit limit);

    @Query("""
            select e from CommissionLedgerEntry e
            where e.status = br.com.gestorcobranca.incentive.domain.model.ledger.EntryStatus.CLEARED
              and e.periodStart = :periodStart
            """)
    List<CommissionLedgerEntry> findCleared(LocalDate periodStart);

    @Query("""
            select e from CommissionLedgerEntry e
            where e.status = br.com.gestorcobranca.incentive.domain.model.ledger.EntryStatus.CLEARED
              and e.periodStart = :periodStart and e.beneficiaryType = :type and e.beneficiaryId = :beneficiaryId
            """)
    List<CommissionLedgerEntry> findCleared(LocalDate periodStart, BeneficiaryType type, UUID beneficiaryId);

    @Query("""
            select e from CommissionLedgerEntry e
            where e.status = br.com.gestorcobranca.incentive.domain.model.ledger.EntryStatus.PENDING
              and e.beneficiaryType = :type and e.beneficiaryId = :beneficiaryId
            """)
    List<CommissionLedgerEntry> findPending(BeneficiaryType type, UUID beneficiaryId);

    @Query("""
            select e from CommissionLedgerEntry e
            where e.beneficiaryType = :type and e.beneficiaryId = :beneficiaryId and e.periodStart = :periodStart
              and (:allCreditors = true or e.creditorId in :creditorIds)
            order by e.paidOn desc, e.createdAt desc
            """)
    Page<CommissionLedgerEntry> findByBeneficiaryInPeriod(BeneficiaryType type, UUID beneficiaryId, LocalDate periodStart,
                                                          boolean allCreditors, Collection<UUID> creditorIds,
                                                          Pageable pageable);

    @Query("""
            select e from CommissionLedgerEntry e
            where e.beneficiaryType = :type and e.beneficiaryId = :beneficiaryId
              and (:allCreditors = true or e.creditorId in :creditorIds)
            order by e.paidOn desc, e.createdAt desc
            """)
    Page<CommissionLedgerEntry> findByBeneficiary(BeneficiaryType type, UUID beneficiaryId, boolean allCreditors,
                                                  Collection<UUID> creditorIds, Pageable pageable);

    List<CommissionLedgerEntry> findByStatementIdOrderByPaidOnAsc(UUID statementId);
}
