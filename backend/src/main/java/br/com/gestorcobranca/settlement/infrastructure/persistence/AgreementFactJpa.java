package br.com.gestorcobranca.settlement.infrastructure.persistence;

import br.com.gestorcobranca.settlement.domain.model.AgreementFact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface AgreementFactJpa extends JpaRepository<AgreementFact, UUID> {
    Optional<AgreementFact> findByAgreementId(UUID agreementId);

    List<AgreementFact> findByCreditorIdAndFormalizedAtGreaterThanEqualAndFormalizedAtLessThan(UUID creditorId,
                                                                                               Instant from,
                                                                                               Instant to);

    List<AgreementFact> findByCreditorIdAndStatusIn(UUID creditorId, Collection<String> statuses);
}
