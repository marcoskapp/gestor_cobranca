package br.com.gestorcobranca.debt.infrastructure.persistence;

import br.com.gestorcobranca.debt.domain.model.ContactType;
import br.com.gestorcobranca.debt.domain.model.DebtorContact;
import br.com.gestorcobranca.debt.domain.repository.DebtorContactRepository;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

interface DebtorContactJpaRepository extends JpaRepository<DebtorContact, UUID>, DebtorContactRepository {

    @Override
    List<DebtorContact> findByDebtorIdOrderByTypeAscPriorityAscCreatedAtAsc(UUID debtorId);

    @Override
    List<DebtorContact> findByDebtorIdAndTypeOrderByPriorityAscCreatedAtAsc(UUID debtorId, ContactType type);

    @Override
    boolean existsByDebtorIdAndTypeAndValueHash(UUID debtorId, ContactType type, String valueHash);

    @Override
    default List<UUID> findDebtorIdsByTypeAndValueHash(ContactType type, String valueHash,
                                                       Collection<UUID> allowedCreditorIds, int limit) {
        if (allowedCreditorIds == null) {
            return debtorIdsByTypeAndValueHash(type, valueHash, Limit.of(limit));
        }
        return allowedCreditorIds.isEmpty() ? List.of()
                : debtorIdsByTypeAndValueHashInCreditors(type, valueHash, allowedCreditorIds, Limit.of(limit));
    }

    /** Usa {@code ix_debtor_contact_hash (tenant_id, type, value_hash)}. */
    @Query("""
            select distinct c.debtorId from DebtorContact c
             where c.type = :type and c.valueHash = :valueHash
             order by c.debtorId
            """)
    List<UUID> debtorIdsByTypeAndValueHash(ContactType type, String valueHash, Limit limit);

    /** Mesmo escopo de {@code DebtorVisibility}: título num dos credores ou coobrigação vigente de contrato de um deles. */
    @Query("""
            select distinct c.debtorId from DebtorContact c
             where c.type = :type and c.valueHash = :valueHash
               and (exists (select 1 from DebtTitle t where t.debtorId = c.debtorId and t.creditorId in :creditorIds)
                    or exists (select 1 from ObligationParty p where p.debtorId = c.debtorId and p.endedOn is null
                                  and p.creditorId in :creditorIds))
             order by c.debtorId
            """)
    List<UUID> debtorIdsByTypeAndValueHashInCreditors(ContactType type, String valueHash, Collection<UUID> creditorIds,
                                                      Limit limit);
}
