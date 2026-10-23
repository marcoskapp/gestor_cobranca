package br.com.gestorcobranca.settlement.domain.repository;

import br.com.gestorcobranca.settlement.domain.model.AgreementFact;
import br.com.gestorcobranca.settlement.domain.model.ReimbursableExpense;
import br.com.gestorcobranca.settlement.domain.model.ReversalNotice;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Fatos de outros módulos materializados por evento: estornos, acordos e despesas do jurídico. */
public interface SettlementFactRepository {

    ReversalNotice save(ReversalNotice notice);

    Optional<ReversalNotice> findNotice(UUID reversalId);

    List<ReversalNotice> findNoticesByAllocation(UUID allocationId);

    AgreementFact save(AgreementFact fact);

    Optional<AgreementFact> findAgreement(UUID agreementId);

    List<AgreementFact> findAgreementsFormalizedBetween(UUID creditorId, Instant from, Instant toExclusive);

    List<AgreementFact> findOpenAgreements(UUID creditorId);

    ReimbursableExpense save(ReimbursableExpense expense);

    Optional<ReimbursableExpense> findExpense(UUID legalExpenseId, ReimbursableExpense.Kind kind);

    /** Despesas ainda não prestadas (ativas) com competência até a data. */
    List<ReimbursableExpense> findOpenExpenses(UUID creditorId, LocalDate until);

    List<ReimbursableExpense> findExpensesByStatement(UUID statementId);
}
