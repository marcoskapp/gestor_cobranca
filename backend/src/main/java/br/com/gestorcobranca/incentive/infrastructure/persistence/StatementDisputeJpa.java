package br.com.gestorcobranca.incentive.infrastructure.persistence;

import br.com.gestorcobranca.incentive.domain.model.statement.StatementDispute;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface StatementDisputeJpa extends JpaRepository<StatementDispute, UUID> {

    List<StatementDispute> findByStatementIdOrderByOpenedAtAsc(UUID statementId);
}
