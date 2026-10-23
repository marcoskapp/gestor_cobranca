package br.com.gestorcobranca.compliance.domain.repository;

import br.com.gestorcobranca.compliance.domain.model.RetentionCategory;
import br.com.gestorcobranca.compliance.domain.model.RetentionRun;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.UUID;

public interface RetentionRunRepository {

    RetentionRun save(RetentionRun run);

    Optional<RetentionRun> findById(UUID id);

    Optional<RetentionRun> findByCategoryAndStatus(RetentionCategory category, RetentionRun.Status status);

    Page<RetentionRun> findAllByOrderByStartedAtDesc(Pageable pageable);
}
