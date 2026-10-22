package br.com.gestorcobranca.debt.domain.repository;

import br.com.gestorcobranca.debt.domain.model.OriginContract;

import java.util.Optional;
import java.util.UUID;

public interface OriginContractRepository {

    OriginContract save(OriginContract contract);

    Optional<OriginContract> findById(UUID id);

    Optional<OriginContract> findByCreditorIdAndExternalId(UUID creditorId, String externalId);
}
