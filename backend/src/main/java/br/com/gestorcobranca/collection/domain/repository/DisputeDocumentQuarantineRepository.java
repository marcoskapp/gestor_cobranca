package br.com.gestorcobranca.collection.domain.repository;

import br.com.gestorcobranca.collection.domain.model.DisputeDocumentQuarantine;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DisputeDocumentQuarantineRepository {

    DisputeDocumentQuarantine save(DisputeDocumentQuarantine document);

    Optional<DisputeDocumentQuarantine> findById(UUID id);

    List<DisputeDocumentQuarantine> findByDisputeId(UUID disputeId);

    long countByDisputeIdAndStatus(UUID disputeId, DisputeDocumentQuarantine.Status status);

    List<DisputeDocumentQuarantine> findByMailReplyId(UUID mailReplyId);
}
