package br.com.gestorcobranca.legal.infrastructure.persistence;

import br.com.gestorcobranca.legal.domain.model.LegalDocument;
import br.com.gestorcobranca.legal.domain.repository.LegalDocumentRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface LegalDocumentJpaRepository extends JpaRepository<LegalDocument, UUID>, LegalDocumentRepository {

    @Override
    Optional<LegalDocument> findById(UUID id);
}
