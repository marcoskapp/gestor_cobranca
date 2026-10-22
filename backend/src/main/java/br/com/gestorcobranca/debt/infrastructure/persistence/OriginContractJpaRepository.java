package br.com.gestorcobranca.debt.infrastructure.persistence;

import br.com.gestorcobranca.debt.domain.model.OriginContract;
import br.com.gestorcobranca.debt.domain.repository.OriginContractRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface OriginContractJpaRepository extends JpaRepository<OriginContract, UUID>, OriginContractRepository {

    @Override
    Optional<OriginContract> findByCreditorIdAndExternalId(UUID creditorId, String externalId);
}
