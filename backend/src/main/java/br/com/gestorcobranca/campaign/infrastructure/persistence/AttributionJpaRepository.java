package br.com.gestorcobranca.campaign.infrastructure.persistence;

import br.com.gestorcobranca.campaign.domain.model.Attribution;
import br.com.gestorcobranca.campaign.domain.model.AttributionKind;
import br.com.gestorcobranca.campaign.domain.repository.AttributionRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

interface AttributionJpaRepository extends JpaRepository<Attribution, UUID>, AttributionRepository {

    @Override
    default boolean exists(UUID enrollmentId, AttributionKind kind, UUID referenceId) {
        return existsByEnrollmentIdAndKindAndReferenceId(enrollmentId, kind, referenceId);
    }

    boolean existsByEnrollmentIdAndKindAndReferenceId(UUID enrollmentId, AttributionKind kind, UUID referenceId);

    @Override
    default boolean existsAny(UUID enrollmentId) {
        return existsByEnrollmentId(enrollmentId);
    }

    boolean existsByEnrollmentId(UUID enrollmentId);

    @Override
    @Query("select distinct a.enrollmentId from Attribution a where a.kind = :kind and a.referenceId = :referenceId")
    List<UUID> enrollmentIdsFor(AttributionKind kind, UUID referenceId);
}
