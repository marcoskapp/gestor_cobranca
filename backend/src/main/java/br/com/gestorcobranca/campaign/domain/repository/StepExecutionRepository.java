package br.com.gestorcobranca.campaign.domain.repository;

import br.com.gestorcobranca.campaign.domain.model.StepExecution;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StepExecutionRepository {

    StepExecution save(StepExecution execution);

    StepExecution saveAndFlush(StepExecution execution);

    /** Execução efetiva ou pulada já registrada para a chave (idempotência). */
    Optional<StepExecution> findCompletedByKey(String idempotencyKey);

    Optional<StepExecution> findByMessageId(UUID messageId);

    Optional<StepExecution> findById(UUID id);

    List<StepExecution> findByEnrollmentIdOrderByExecutedAt(UUID enrollmentId);
}
