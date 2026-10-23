package br.com.gestorcobranca.compliance.infrastructure.persistence;

import br.com.gestorcobranca.compliance.domain.model.RetentionCategory;
import br.com.gestorcobranca.compliance.domain.model.RetentionRun;
import br.com.gestorcobranca.compliance.domain.repository.RetentionRunRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface RetentionRunJpaRepository extends JpaRepository<RetentionRun, UUID>, RetentionRunRepository {

    @Override
    Optional<RetentionRun> findByCategoryAndStatus(RetentionCategory category, RetentionRun.Status status);

    @Override
    Page<RetentionRun> findAllByOrderByStartedAtDesc(Pageable pageable);
}
