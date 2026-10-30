package br.com.gestorcobranca.ingestion.domain.repository;

import br.com.gestorcobranca.shared.paging.ChangeQuery;

import br.com.gestorcobranca.ingestion.domain.model.ImportBatch;
import br.com.gestorcobranca.ingestion.domain.model.ImportBatchStatus;
import br.com.gestorcobranca.ingestion.domain.model.ImportMode;
import br.com.gestorcobranca.ingestion.domain.model.ImportSource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ImportBatchRepository {

    ImportBatch save(ImportBatch batch);

    Optional<ImportBatch> findById(UUID id);

    /** Lotes da carteira com o mesmo conteúdo, modo e status, do mais recente ao mais antigo. */
    List<ImportBatch> findByPortfolioIdAndFileSha256AndModeAndStatusInOrderByCreatedAtDesc(
            UUID portfolioId, String fileSha256, ImportMode mode, Collection<ImportBatchStatus> statuses);

    /**
     * @param allowedCreditorIds credores visíveis ao ator; {@code null} = sem restrição
     */
    Page<ImportBatch> search(Collection<UUID> allowedCreditorIds, Criteria criteria, Pageable pageable);

    /** Leitura incremental: até {@code limit + 1} lotes do credor alterados na janela, depois do cursor. */
    List<ImportBatch> findChanges(UUID creditorId, ChangeQuery query);

    /** Filtros opcionais da listagem de lotes. */
    record Criteria(UUID creditorId, UUID portfolioId, ImportBatchStatus status, ImportSource source,
                    Instant createdFrom, Instant createdTo) {
    }
}
