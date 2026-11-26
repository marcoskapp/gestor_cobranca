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

    /** Dentre os identificadores informados, os de contratos do credor cujo devedor está anonimizado. */
    List<String> findExternalIdsOfAnonymizedDebtors(UUID creditorId, Collection<String> externalIds);

    /** Devedores anonimizados donos destes identificadores no credor. */
    List<UUID> findAnonymizedDebtorIds(UUID creditorId, Collection<String> externalIds);

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
