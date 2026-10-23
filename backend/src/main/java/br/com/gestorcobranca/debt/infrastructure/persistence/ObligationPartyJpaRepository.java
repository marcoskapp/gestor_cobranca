package br.com.gestorcobranca.debt.infrastructure.persistence;

import br.com.gestorcobranca.debt.domain.model.ObligationParty;
import br.com.gestorcobranca.debt.domain.model.ObligationRole;
import br.com.gestorcobranca.debt.domain.repository.ObligationPartyRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ObligationPartyJpaRepository extends JpaRepository<ObligationParty, UUID>, ObligationPartyRepository {

    @Override
    List<ObligationParty> findByOriginContractIdOrderByCreatedAtAsc(UUID originContractId);

    @Override
    Optional<ObligationParty> findByOriginContractIdAndDebtorIdAndRole(UUID originContractId, UUID debtorId,
                                                                       ObligationRole role);

    @Override
    List<ObligationParty> findByDebtorIdAndEndedOnIsNullOrderByCreatedAtAsc(UUID debtorId);

    @Override
    @Query("select distinct p.creditorId from ObligationParty p where p.debtorId = :debtorId and p.endedOn is null")
    List<UUID> findActiveCreditorIdsByDebtorId(UUID debtorId);

    @Override
    @Query("""
            select distinct p.debtorId from ObligationParty p
            where p.debtorId in :debtorIds and p.creditorId in :creditorIds and p.endedOn is null
            """)
    List<UUID> findActiveDebtorIdsInCreditors(Collection<UUID> debtorIds, Collection<UUID> creditorIds);

    @Override
    @Query("""
            select case when count(p) > 0 then true else false end from ObligationParty p
            where p.debtorId = :debtorId and p.endedOn is null
            and exists (select 1 from DebtTitle t where t.originContractId = p.originContractId
                        and t.status in (br.com.gestorcobranca.debt.domain.model.TitleStatus.OPEN,
                                         br.com.gestorcobranca.debt.domain.model.TitleStatus.SUSPENDED))
            """)
    boolean existsActiveWithOpenTitles(UUID debtorId);
}
