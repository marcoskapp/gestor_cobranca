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

    /** Grava já (antes de instruções SQL diretas sobre a mesma linha, como o descarte do arquivo). */
    ImportBatch saveAndFlush(ImportBatch batch);

    Optional<ImportBatch> findById(UUID id);

    /** Trava o lote até o fim da transação (bloqueio × encerramento do processamento; SEG-20261124-D01h). */
    Optional<ImportBatch> findByIdForUpdate(UUID id);

    /** Trava os lotes numa instrução, na ordem de id do banco (a mesma de qualquer outro SQL que os trave). */
    List<ImportBatch> findAllByIdInForUpdate(Collection<UUID> ids);

    /** Leitura de vários lotes (rótulos da lista "Baixas informadas pelo credor"); sem lock. */
    List<ImportBatch> findAllByIdIn(Collection<UUID> ids);

    /** Algum lote do credor com este conteúdo foi bloqueado (reenvio recusado). */
    boolean existsByCreditorIdAndFileSha256AndBlockedReasonIsNotNull(UUID creditorId, String fileSha256);

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
                    Instant createdFrom, Instant createdTo, BlockedFilter blocked) {
    }

    /** Filtro dos lotes bloqueados por titular anonimizado (nulo = sem filtro). */
    enum BlockedFilter { ALL, PENDING_REQUEST, REQUESTED }
}
