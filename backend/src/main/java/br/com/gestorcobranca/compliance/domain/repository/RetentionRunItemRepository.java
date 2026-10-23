package br.com.gestorcobranca.compliance.domain.repository;

import br.com.gestorcobranca.compliance.domain.model.RetentionRunItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface RetentionRunItemRepository {

    RetentionRunItem save(RetentionRunItem item);

    Page<RetentionRunItem> findByRunIdOrderByProcessedAtAsc(UUID runId, Pageable pageable);
}
