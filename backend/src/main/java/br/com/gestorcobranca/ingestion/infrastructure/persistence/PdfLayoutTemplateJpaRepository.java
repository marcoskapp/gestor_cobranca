package br.com.gestorcobranca.ingestion.infrastructure.persistence;

import br.com.gestorcobranca.ingestion.domain.model.pdf.PdfLayoutTemplate;
import br.com.gestorcobranca.ingestion.domain.repository.PdfLayoutTemplateRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

interface PdfLayoutTemplateJpaRepository extends JpaRepository<PdfLayoutTemplate, UUID>, PdfLayoutTemplateRepository {

    @Override
    @Query("""
            select t from PdfLayoutTemplate t
             where t.creditorId = :creditorId and t.active = true
             order by t.lastUsedAt desc nulls last, t.createdAt desc
            """)
    List<PdfLayoutTemplate> findActiveByCreditor(@Param("creditorId") UUID creditorId);
}
