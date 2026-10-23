package br.com.gestorcobranca.incentive.infrastructure.persistence;

import br.com.gestorcobranca.incentive.domain.model.attribution.AgreementAttribution;
import br.com.gestorcobranca.incentive.domain.repository.AgreementAttributionRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface AgreementAttributionJpaRepository extends JpaRepository<AgreementAttribution, UUID>, AgreementAttributionRepository {

    @Override
    Optional<AgreementAttribution> findByAgreementId(UUID agreementId);

    @Override
    boolean existsByAgreementId(UUID agreementId);

    @Override
    List<AgreementAttribution> findAllByIdIn(Collection<UUID> ids);
}
