package br.com.gestorcobranca.compliance.infrastructure.persistence;

import br.com.gestorcobranca.compliance.domain.model.RetentionRunItem;
import br.com.gestorcobranca.compliance.domain.repository.RetentionRunItemRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface RetentionRunItemJpaRepository extends JpaRepository<RetentionRunItem, UUID>, RetentionRunItemRepository {

    @Override
    Page<RetentionRunItem> findByRunIdOrderByProcessedAtAsc(UUID runId, Pageable pageable);
}
