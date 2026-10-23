package br.com.gestorcobranca.ingestion.domain.repository;

import br.com.gestorcobranca.ingestion.domain.model.ImportRowIssue;
import br.com.gestorcobranca.ingestion.domain.model.IssueSeverity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

public interface ImportRowIssueRepository {

    <S extends ImportRowIssue> List<S> saveAll(Iterable<S> issues);

    long countByBatchId(UUID batchId);

    Page<ImportRowIssue> findByBatchId(UUID batchId, Pageable pageable);

    Page<ImportRowIssue> findByBatchIdAndSeverity(UUID batchId, IssueSeverity severity, Pageable pageable);
}
