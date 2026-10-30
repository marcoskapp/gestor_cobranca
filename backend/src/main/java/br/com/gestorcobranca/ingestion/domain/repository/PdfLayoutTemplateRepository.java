package br.com.gestorcobranca.ingestion.domain.repository;

import br.com.gestorcobranca.ingestion.domain.model.pdf.PdfLayoutTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PdfLayoutTemplateRepository {

    PdfLayoutTemplate save(PdfLayoutTemplate template);

    Optional<PdfLayoutTemplate> findById(UUID id);

    /** Mapas ativos do credor, os usados mais recentemente primeiro. */
    List<PdfLayoutTemplate> findActiveByCreditor(UUID creditorId);

    List<PdfLayoutTemplate> findByCreditorIdOrderByNameAscTemplateVersionDesc(UUID creditorId);

    Optional<PdfLayoutTemplate> findFirstByCreditorIdAndFingerprintOrderByTemplateVersionDesc(UUID creditorId,
                                                                                             String fingerprint);
}
