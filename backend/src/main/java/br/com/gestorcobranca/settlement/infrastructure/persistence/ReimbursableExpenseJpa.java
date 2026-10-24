package br.com.gestorcobranca.settlement.infrastructure.persistence;

import br.com.gestorcobranca.settlement.domain.model.ReimbursableExpense;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ReimbursableExpenseJpa extends JpaRepository<ReimbursableExpense, UUID> {
    Optional<ReimbursableExpense> findByLegalExpenseIdAndKind(UUID legalExpenseId, ReimbursableExpense.Kind kind);

    List<ReimbursableExpense> findByCreditorIdAndStatementIdIsNullAndStatusAndCompetenceDateLessThanEqual(
            UUID creditorId, ReimbursableExpense.Status status, LocalDate until);

    List<ReimbursableExpense> findByStatementIdOrderByCompetenceDateAsc(UUID statementId);
}
