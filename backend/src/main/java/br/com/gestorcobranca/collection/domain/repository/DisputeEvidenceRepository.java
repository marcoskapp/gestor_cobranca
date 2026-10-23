package br.com.gestorcobranca.collection.domain.repository;

import br.com.gestorcobranca.collection.domain.model.DisputeEvidence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DisputeEvidenceRepository {

    DisputeEvidence save(DisputeEvidence evidence);

    Optional<DisputeEvidence> findById(UUID id);

    List<DisputeEvidence> findByDisputeIdOrderByCreatedAtAsc(UUID disputeId);

    long countByDisputeId(UUID disputeId);
}
