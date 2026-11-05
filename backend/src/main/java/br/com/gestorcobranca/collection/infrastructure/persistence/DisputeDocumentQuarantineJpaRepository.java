package br.com.gestorcobranca.collection.infrastructure.persistence;

import br.com.gestorcobranca.collection.domain.model.DisputeDocumentQuarantine;
import br.com.gestorcobranca.collection.domain.repository.DisputeDocumentQuarantineRepository;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Sem remoção: o registro da verificação fica (inclusive o de arquivo infectado, sem o conteúdo). */
interface DisputeDocumentQuarantineJpaRepository extends Repository<DisputeDocumentQuarantine, UUID>,
        DisputeDocumentQuarantineRepository {

    @Override
    DisputeDocumentQuarantine save(DisputeDocumentQuarantine document);

    @Override
    Optional<DisputeDocumentQuarantine> findById(UUID id);

    @Override
    List<DisputeDocumentQuarantine> findByDisputeId(UUID disputeId);

    @Override
    long countByDisputeIdAndStatus(UUID disputeId, DisputeDocumentQuarantine.Status status);
}
