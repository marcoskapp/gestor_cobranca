package br.com.gestorcobranca.debt.infrastructure.persistence;

import br.com.gestorcobranca.debt.domain.model.ReversalHold;
import br.com.gestorcobranca.debt.domain.model.ReversalHoldState;
import br.com.gestorcobranca.debt.domain.repository.ReversalHoldRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

interface ReversalHoldJpaRepository extends JpaRepository<ReversalHold, UUID>, ReversalHoldRepository {

    /** Id zero: preenche a lista de credores quando o escopo é total (a condição é ignorada). */
    UUID NO_CREDITOR = new UUID(0L, 0L);

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from ReversalHold h where h.id = :id")
    Optional<ReversalHold> findByIdForUpdate(@Param("id") UUID id);

    @Override
    @Query("select h.creditorId as creditorId, h.debtorId as debtorId from ReversalHold h where h.id = :id")
    Optional<Parties> findPartiesById(@Param("id") UUID id);

    @Override
    boolean existsByReversalId(UUID reversalId);

    @Override
    @Query(value = """
            select exists (select 1 from debt.reversal_hold h
                            where h.state = 'PENDING' and h.creditor_id = :creditor and h.debtor_id = :debtor)
                or exists (select 1
                             from debt.reversal_hold h
                             join debt.reversal_hold_item i on i.hold_id = h.id and i.kind = 'TITLE'
                             join debt.debt_title t on t.id = i.item_id
                             join debt.obligation_party op on op.origin_contract_id = t.origin_contract_id
                            where h.state = 'PENDING' and h.creditor_id = :creditor
                              and op.debtor_id = :debtor and op.ended_on is null)
            """, nativeQuery = true)
    boolean existsPendingForContact(@Param("creditor") UUID creditorId, @Param("debtor") UUID debtorId);

    @Query(value = """
            select distinct i.item_id
              from debt.reversal_hold h join debt.reversal_hold_item i on i.hold_id = h.id and i.kind = 'TITLE'
             where h.state = 'PENDING' and h.creditor_id = :creditor and h.debtor_id = :debtor
            """, nativeQuery = true)
    List<UUID> pendingTitleIds(@Param("creditor") UUID creditorId, @Param("debtor") UUID debtorId);

    @Override
    default Set<UUID> findPendingTitleIds(UUID creditorId, UUID debtorId) {
        return new HashSet<>(pendingTitleIds(creditorId, debtorId));
    }

    @Query(value = """
            select distinct i.item_id
              from debt.reversal_hold h join debt.reversal_hold_item i on i.hold_id = h.id and i.kind = 'TITLE'
             where h.state = 'PENDING' and i.item_id in (:ids)
            """, nativeQuery = true)
    List<UUID> pendingTitleIdsIn(@Param("ids") Collection<UUID> titleIds);

    @Override
    default Set<UUID> findPendingTitleIdsIn(Collection<UUID> titleIds) {
        return titleIds == null || titleIds.isEmpty() ? Set.of() : new HashSet<>(pendingTitleIdsIn(titleIds));
    }

    @Query(value = """
            select distinct i.item_id
              from debt.reversal_hold_item a
              join debt.reversal_hold h on h.id = a.hold_id
              join debt.reversal_hold_item i on i.hold_id = h.id and i.kind = 'INSTALLMENT'
             where a.kind = 'AGREEMENT' and a.item_id = :agreement
               and (h.state = 'PENDING' or (h.state = 'RELEASED' and h.released_at >= :since))
            """, nativeQuery = true)
    List<UUID> breakExemptInstallmentIds(@Param("agreement") UUID agreementId, @Param("since") Instant releasedSince);

    @Override
    default Set<UUID> findBreakExemptInstallmentIds(UUID agreementId, Instant releasedSince) {
        return new HashSet<>(breakExemptInstallmentIds(agreementId, releasedSince));
    }

    @Query(value = """
            select distinct i.item_id
              from debt.reversal_hold_item a
              join debt.reversal_hold h on h.id = a.hold_id
              join debt.reversal_hold_item i on i.hold_id = h.id and i.kind = 'INSTALLMENT'
             where a.kind = 'AGREEMENT' and a.item_id = :agreement and h.state = 'PENDING'
            """, nativeQuery = true)
    List<UUID> pendingInstallmentIds(@Param("agreement") UUID agreementId);

    @Override
    default Set<UUID> findPendingInstallmentIds(UUID agreementId) {
        return new HashSet<>(pendingInstallmentIds(agreementId));
    }

    @Query(value = """
            select distinct on (own.debtor_id, own.creditor_id) own.id
              from debt.debt_title t
              join debt.obligation_party op on op.origin_contract_id = t.origin_contract_id and op.ended_on is null
                                           and op.debtor_id <> t.debtor_id
              join debt.debt_title own on own.debtor_id = op.debtor_id and own.creditor_id = t.creditor_id
             where t.id in (:ids)
             order by own.debtor_id, own.creditor_id, (own.status in ('OPEN', 'SUSPENDED')) desc, own.id
            """, nativeQuery = true)
    List<UUID> coobligorTitleIds(@Param("ids") Collection<UUID> titleIds);

    @Override
    default Set<UUID> findCoobligorTitleIds(Collection<UUID> titleIds) {
        return titleIds == null || titleIds.isEmpty() ? Set.of() : new HashSet<>(coobligorTitleIds(titleIds));
    }

    @Override
    @Query(value = """
            select exists (select 1 from debt.reversal_hold_item i
                            where i.kind = 'ALLOCATION' and i.item_id = :allocation)
            """, nativeQuery = true)
    boolean existsByAllocationId(@Param("allocation") UUID allocationId);

    @Override
    List<ReversalHold> findByDebtorId(UUID debtorId);

    @Override
    List<ReversalHold> findByPaymentIdOrderByPlacedAtDesc(UUID paymentId);

    @Override
    List<ReversalHold> findByDebtorIdAndState(UUID debtorId, ReversalHoldState state);

    @Query(value = """
            select h from ReversalHold h
             where (:all = true or h.creditorId in (:creditors))
               and (:creditor is null or h.creditorId = :creditor)
               and (:payment is null or h.paymentId = :payment)
               and (:state is null or cast(h.state as string) = :state)
               and h.placedAt >= :from and h.placedAt < :to
            """, countQuery = """
            select count(h) from ReversalHold h
             where (:all = true or h.creditorId in (:creditors))
               and (:creditor is null or h.creditorId = :creditor)
               and (:payment is null or h.paymentId = :payment)
               and (:state is null or cast(h.state as string) = :state)
               and h.placedAt >= :from and h.placedAt < :to
            """)
    Page<ReversalHold> searchScoped(@Param("creditors") Collection<UUID> creditors, @Param("all") boolean all,
                                    @Param("creditor") UUID creditorId, @Param("payment") UUID paymentId,
                                    @Param("state") String state,
                                    @Param("from") Instant from, @Param("to") Instant to, Pageable pageable);

    @Override
    default Page<ReversalHold> search(Collection<UUID> creditors, boolean allCreditors, UUID creditorId,
                                      UUID paymentId, String state, Instant from, Instant to, Pageable pageable) {
        Collection<UUID> scope = allCreditors || creditors == null || creditors.isEmpty() ? List.of(NO_CREDITOR) : creditors;
        return searchScoped(scope, allCreditors, creditorId, paymentId, state, from, to, pageable);
    }

    @Override
    @Query(value = """
            select h.creditorId as creditorId, count(h) as total from ReversalHold h
             where h.creditorId in (:creditors) and h.placedAt >= :since
             group by h.creditorId
            """)
    List<CreditorCount> countByCreditorSince(@Param("creditors") Collection<UUID> creditorIds,
                                             @Param("since") Instant since);
}
