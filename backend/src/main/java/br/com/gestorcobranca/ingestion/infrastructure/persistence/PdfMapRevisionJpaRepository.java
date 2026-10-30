package br.com.gestorcobranca.ingestion.infrastructure.persistence;

import br.com.gestorcobranca.ingestion.domain.model.pdf.PdfMapRevision;
import br.com.gestorcobranca.ingestion.domain.repository.PdfMapRevisionRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface PdfMapRevisionJpaRepository extends JpaRepository<PdfMapRevision, UUID>, PdfMapRevisionRepository {
}
