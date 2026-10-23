package br.com.gestorcobranca.debt.domain.repository;

import br.com.gestorcobranca.debt.domain.model.OriginContract;

import br.com.gestorcobranca.debt.domain.model.InstrumentType;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OriginContractRepository {

    OriginContract save(OriginContract contract);

    Optional<OriginContract> findById(UUID id);

    Optional<OriginContract> findByCreditorIdAndExternalId(UUID creditorId, String externalId);

    /** Lock pessimista: serializa a reclassificação e o recálculo de prescrição do contrato. */
    Optional<OriginContract> findByIdForUpdate(UUID id);

    List<OriginContract> findAllByIdIn(Collection<UUID> ids);

    /** Contratos em que o devedor é o devedor principal. */
    List<OriginContract> findByDebtorId(UUID debtorId);

    /**
     * Ids dos contratos de uma natureza, em ordem de id, após {@code afterId} (paginação por
     * cursor para recálculos em lote).
     */
    List<UUID> findIdsByInstrumentTypeAfter(InstrumentType instrumentType, UUID afterId, int limit);
}
