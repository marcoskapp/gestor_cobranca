package br.com.gestorcobranca.debt.infrastructure.persistence;

import br.com.gestorcobranca.debt.domain.model.InstrumentType;
import br.com.gestorcobranca.debt.domain.model.OriginContract;
import br.com.gestorcobranca.debt.domain.repository.OriginContractRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface OriginContractJpaRepository extends JpaRepository<OriginContract, UUID>, OriginContractRepository {

    @Override
    List<OriginContract> findByCreditorIdAndExternalIdIn(UUID creditorId, Collection<String> storedExternalIds);

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from OriginContract c where c.id = :id")
    Optional<OriginContract> findByIdForUpdate(UUID id);

    @Override
    @Query("""
            select c.externalId from OriginContract c, Debtor d
            where d.id = c.debtorId and c.creditorId = :creditorId and c.externalId in :externalIds
              and d.status = br.com.gestorcobranca.debt.domain.model.DebtorStatus.ANONYMIZED
            """)
    List<String> findExternalIdsOfAnonymizedDebtors(UUID creditorId, Collection<String> externalIds);

    @Override
    @Query("""
            select distinct d.id from OriginContract c, Debtor d
            where d.id = c.debtorId and c.creditorId = :creditorId and c.externalId in :externalIds
              and d.status = br.com.gestorcobranca.debt.domain.model.DebtorStatus.ANONYMIZED
            """)
    List<UUID> findAnonymizedDebtorIds(UUID creditorId, Collection<String> externalIds);

    @Override
    List<OriginContract> findAllByIdIn(Collection<UUID> ids);

    @Override
    @Query("select c.id from OriginContract c where c.debtorId = :debtorId order by c.id")
    List<UUID> findIdsByDebtorId(UUID debtorId);

    @Override
    List<OriginContract> findByDebtorId(UUID debtorId);

    @Override
    default List<UUID> findIdsByInstrumentTypeAfter(InstrumentType instrumentType, UUID afterId, int limit) {
        return findIdsAfter(instrumentType, afterId == null ? new UUID(0L, 0L) : afterId, Limit.of(limit));
    }

    @Query("select c.id from OriginContract c where c.instrumentType = :instrumentType and c.id > :afterId order by c.id")
    List<UUID> findIdsAfter(InstrumentType instrumentType, UUID afterId, Limit limit);
}
