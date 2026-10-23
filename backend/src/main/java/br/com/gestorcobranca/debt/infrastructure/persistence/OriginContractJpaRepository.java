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
    Optional<OriginContract> findByCreditorIdAndExternalId(UUID creditorId, String externalId);

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from OriginContract c where c.id = :id")
    Optional<OriginContract> findByIdForUpdate(UUID id);

    @Override
    List<OriginContract> findAllByIdIn(Collection<UUID> ids);

    @Override
    List<OriginContract> findByDebtorId(UUID debtorId);

    @Override
    default List<UUID> findIdsByInstrumentTypeAfter(InstrumentType instrumentType, UUID afterId, int limit) {
        return findIdsAfter(instrumentType, afterId == null ? new UUID(0L, 0L) : afterId, Limit.of(limit));
    }

    @Query("select c.id from OriginContract c where c.instrumentType = :instrumentType and c.id > :afterId order by c.id")
    List<UUID> findIdsAfter(InstrumentType instrumentType, UUID afterId, Limit limit);
}
