package br.com.gestorcobranca.complaint.infrastructure.persistence;

import br.com.gestorcobranca.complaint.domain.model.ComplaintEvidence;
import br.com.gestorcobranca.complaint.domain.repository.ComplaintEvidenceRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface ComplaintEvidenceJpaRepository extends JpaRepository<ComplaintEvidence, UUID>, ComplaintEvidenceRepository {
}
