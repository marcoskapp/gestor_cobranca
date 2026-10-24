package br.com.gestorcobranca.ingestion.domain.repository;

import br.com.gestorcobranca.ingestion.domain.model.review.AlertStatus;
import br.com.gestorcobranca.ingestion.domain.model.review.AlertType;
import br.com.gestorcobranca.ingestion.domain.model.review.ImportReviewAlert;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ImportReviewAlertRepository {

    <S extends ImportReviewAlert> List<S> saveAll(Iterable<S> alerts);

    /** Alertas da prévia com filtros opcionais (nulos = todos). */
    Page<ImportReviewAlert> search(UUID batchId, AlertType type, AlertStatus status, Pageable pageable);

    List<ImportReviewAlert> findByBatchIdAndIdIn(UUID batchId, Collection<UUID> ids);

    /** Para decisão em lote por filtro (limitada pelo máximo de alertas por prévia). */
    List<ImportReviewAlert> findByBatchIdAndStatusAndTypeIn(UUID batchId, AlertStatus status, Collection<AlertType> types);

    List<ImportReviewAlert> findByBatchIdAndRowNumberIn(UUID batchId, Collection<Long> rowNumbers);

    long countByBatchIdAndStatus(UUID batchId, AlertStatus status);

    long countByBatchIdAndStatusAndTypeIn(UUID batchId, AlertStatus status, Collection<AlertType> types);

    /** Linhas excluídas na conferência (alertas excluídos, linha > 0). */
    List<Long> excludedRows(UUID batchId);

    /** Contagem por tipo e situação. */
    List<TypeCount> countByType(UUID batchId);

    record TypeCount(AlertType type, AlertStatus status, long count) {
    }
}
