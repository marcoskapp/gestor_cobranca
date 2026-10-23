package br.com.gestorcobranca.campaign.infrastructure.persistence;

import br.com.gestorcobranca.campaign.domain.model.StepExecution;
import br.com.gestorcobranca.campaign.domain.repository.StepExecutionRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

interface StepExecutionJpaRepository extends JpaRepository<StepExecution, UUID>, StepExecutionRepository {

    @Override
    @Query("""
            select s from StepExecution s where s.idempotencyKey = :idempotencyKey
               and s.outcome <> br.com.gestorcobranca.campaign.domain.model.StepOutcome.FAILED
            """)
    Optional<StepExecution> findCompletedByKey(String idempotencyKey);
}
