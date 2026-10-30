package br.com.gestorcobranca.ingestion.domain.repository;

import br.com.gestorcobranca.ingestion.domain.model.pdf.PdfMapRevision;

import java.util.List;
import java.util.UUID;

public interface PdfMapRevisionRepository {

    PdfMapRevision save(PdfMapRevision revision);

    List<PdfMapRevision> findByPdfImportIdOrderByRevisionAsc(UUID pdfImportId);
}
