package br.com.gestorcobranca.collection.infrastructure.persistence;

import br.com.gestorcobranca.collection.domain.model.DisputeEvidence;
import br.com.gestorcobranca.collection.domain.repository.DisputeEvidenceRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface DisputeEvidenceJpaRepository extends JpaRepository<DisputeEvidence, UUID>, DisputeEvidenceRepository {
}
