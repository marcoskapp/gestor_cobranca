package br.com.gestorcobranca.debt.domain.repository;

import br.com.gestorcobranca.debt.domain.model.AssetIdentification;
import br.com.gestorcobranca.debt.domain.model.Collateral;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CollateralRepository {

    Collateral save(Collateral collateral);

    Optional<Collateral> findById(UUID id);

    /** Lock pessimista: serializa avaliações e mudanças de situação concorrentes. */
    Optional<Collateral> findByIdForUpdate(UUID id);

    List<Collateral> findByOriginContractIdOrderByCreatedAtAsc(UUID originContractId);

    List<Collateral> findByOriginContractIdInOrderByCreatedAtAsc(Collection<UUID> originContractIds);

    Optional<Collateral> findByOriginContractIdAndExternalId(UUID originContractId, String externalId);

    /**
     * Garantia do contrato sobre o mesmo bem: veículo por chassi, RENAVAM ou placa; imóvel por
     * matrícula, cartório, município e UF. Outros bens não têm identidade estruturada (vazio).
     */
    Optional<Collateral> findSameAsset(UUID originContractId, AssetIdentification asset);
}
