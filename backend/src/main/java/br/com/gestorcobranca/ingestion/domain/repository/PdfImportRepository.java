package br.com.gestorcobranca.ingestion.domain.repository;

import br.com.gestorcobranca.ingestion.domain.model.pdf.PdfImport;
import br.com.gestorcobranca.ingestion.domain.model.pdf.PdfImportStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PdfImportRepository {

    PdfImport save(PdfImport pdfImport);

    Optional<PdfImport> findById(UUID id);

    /** Bloqueio da linha: aprovação, edição do mapa e job de mapeamento não se cruzam. */
    Optional<PdfImport> findByIdForUpdate(UUID id);

    /** Importação aberta ou aprovada do mesmo conteúdo na carteira (idempotência pelo hash). */
    List<PdfImport> findByPortfolioIdAndFileSha256AndStatusIn(UUID portfolioId, String fileSha256,
                                                              Collection<PdfImportStatus> statuses);

    Optional<PdfImport> findByCreditorIdAndIdempotencyKey(UUID creditorId, String idempotencyKey);

    /** Encerradas há mais tempo que a retenção e com o arquivo ainda guardado. */
    List<PdfImport> findRetentionCandidates(Collection<PdfImportStatus> statuses, Instant updatedBefore, Pageable page);

    /** @param allowedCreditorIds credores visíveis ao ator; {@code null} = sem restrição */
    Page<PdfImport> search(Collection<UUID> allowedCreditorIds, Criteria criteria, Pageable pageable);

    record Criteria(UUID creditorId, UUID portfolioId, Collection<PdfImportStatus> statuses) {
    }
}
