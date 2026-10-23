package br.com.gestorcobranca.campaign.domain.repository;

import br.com.gestorcobranca.campaign.domain.model.Attribution;
import br.com.gestorcobranca.campaign.domain.model.AttributionKind;

import java.util.List;
import java.util.UUID;

public interface AttributionRepository {

    Attribution save(Attribution attribution);

    boolean exists(UUID enrollmentId, AttributionKind kind, UUID referenceId);

    boolean existsAny(UUID enrollmentId);

    /** Inscrições que receberam o resultado (ex.: o pagamento que está sendo estornado). */
    List<UUID> enrollmentIdsFor(AttributionKind kind, UUID referenceId);
}
