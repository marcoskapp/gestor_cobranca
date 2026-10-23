package br.com.gestorcobranca.legal.domain.repository;

import br.com.gestorcobranca.legal.domain.model.LegalDocument;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LegalDocumentRepository {

    LegalDocument save(LegalDocument document);

    Optional<LegalDocument> findById(UUID id);

    List<LegalDocument> findByMatterIdOrderByCreatedAtDesc(UUID matterId);
}
