package br.com.gestorcobranca.incentive.domain.repository;

import br.com.gestorcobranca.incentive.domain.model.attribution.AgreementAttribution;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgreementAttributionRepository {

    AgreementAttribution save(AgreementAttribution attribution);

    Optional<AgreementAttribution> findById(UUID id);

    Optional<AgreementAttribution> findByAgreementId(UUID agreementId);

    boolean existsByAgreementId(UUID agreementId);

    List<AgreementAttribution> findAllByIdIn(Collection<UUID> ids);
}
