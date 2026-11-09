package br.com.gestorcobranca.complaint.domain.repository;

import br.com.gestorcobranca.complaint.domain.model.ComplaintEvidence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ComplaintEvidenceRepository {

    ComplaintEvidence save(ComplaintEvidence evidence);

    Optional<ComplaintEvidence> findById(UUID id);

    List<ComplaintEvidence> findByComplaintIdOrderByCreatedAtAsc(UUID complaintId);

    long countByComplaintIdAndStatusIn(UUID complaintId, java.util.Collection<ComplaintEvidence.Status> statuses);
}
