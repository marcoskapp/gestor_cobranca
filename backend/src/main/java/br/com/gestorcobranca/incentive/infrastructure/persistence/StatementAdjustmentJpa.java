package br.com.gestorcobranca.incentive.infrastructure.persistence;

import br.com.gestorcobranca.incentive.domain.model.statement.StatementAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface StatementAdjustmentJpa extends JpaRepository<StatementAdjustment, UUID> {

    List<StatementAdjustment> findByStatementIdOrderByRequestedAtAsc(UUID statementId);
}
