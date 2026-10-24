package br.com.gestorcobranca.settlement.infrastructure.persistence;

import br.com.gestorcobranca.settlement.domain.model.AgreementFact;
import br.com.gestorcobranca.settlement.domain.model.ReimbursableExpense;
import br.com.gestorcobranca.settlement.domain.model.ReversalNotice;
import br.com.gestorcobranca.settlement.domain.repository.SettlementFactRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
class JpaSettlementFactRepository implements SettlementFactRepository {

    private final ReversalNoticeJpa notices;
    private final AgreementFactJpa agreements;
    private final ReimbursableExpenseJpa expenses;

    JpaSettlementFactRepository(ReversalNoticeJpa notices, AgreementFactJpa agreements,
                                ReimbursableExpenseJpa expenses) {
        this.notices = notices;
        this.agreements = agreements;
        this.expenses = expenses;
    }

    @Override
    public ReversalNotice save(ReversalNotice notice) {
        return notices.save(notice);
    }

    @Override
    public Optional<ReversalNotice> findNotice(UUID reversalId) {
        return notices.findByReversalId(reversalId);
    }

    @Override
    public List<ReversalNotice> findNoticesByAllocation(UUID allocationId) {
        return notices.findByAllocationIdOrderByCreatedAtAsc(allocationId);
    }

    @Override
    public AgreementFact save(AgreementFact fact) {
        return agreements.save(fact);
    }

    @Override
    public Optional<AgreementFact> findAgreement(UUID agreementId) {
        return agreements.findByAgreementId(agreementId);
    }

    @Override
    public List<AgreementFact> findAgreementsFormalizedBetween(UUID creditorId, Instant from, Instant toExclusive) {
        return agreements.findByCreditorIdAndFormalizedAtGreaterThanEqualAndFormalizedAtLessThan(creditorId, from,
                toExclusive);
    }

    @Override
    public List<AgreementFact> findOpenAgreements(UUID creditorId) {
        return agreements.findByCreditorIdAndStatusIn(creditorId, Set.of("FORMALIZED", "IN_PAYMENT"));
    }

    @Override
    public ReimbursableExpense save(ReimbursableExpense expense) {
        return expenses.save(expense);
    }

    @Override
    public Optional<ReimbursableExpense> findExpense(UUID legalExpenseId, ReimbursableExpense.Kind kind) {
        return expenses.findByLegalExpenseIdAndKind(legalExpenseId, kind);
    }

    @Override
    public List<ReimbursableExpense> findOpenExpenses(UUID creditorId, LocalDate until) {
        return expenses.findByCreditorIdAndStatementIdIsNullAndStatusAndCompetenceDateLessThanEqual(creditorId,
                ReimbursableExpense.Status.ACTIVE, until);
    }

    @Override
    public List<ReimbursableExpense> findExpensesByStatement(UUID statementId) {
        return expenses.findByStatementIdOrderByCompetenceDateAsc(statementId);
    }
}
