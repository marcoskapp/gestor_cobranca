package br.com.gestorcobranca.settlement.application.service;

import br.com.gestorcobranca.settlement.application.port.out.SettlementLocks;
import br.com.gestorcobranca.settlement.application.port.out.SettlementSources;
import br.com.gestorcobranca.settlement.domain.model.AgreementFact;
import br.com.gestorcobranca.settlement.domain.model.ReimbursableExpense;
import br.com.gestorcobranca.settlement.domain.repository.SettlementFactRepository;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.events.legal.LegalExpenseChanged;
import br.com.gestorcobranca.shared.events.negotiation.AgreementAttributionRecorded;
import br.com.gestorcobranca.shared.events.negotiation.AgreementStatusChanged;
import br.com.gestorcobranca.shared.config.TimeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;

/**
 * Fatos de outros módulos para o demonstrativo: acordos (valor negociado e comissão prevista) e custas
 * do jurídico ({@code LegalExpenseChanged}). Idempotentes pela chave natural; eventos fora de ordem não
 * regridem a situação.
 */
@Component
class FactIngestionService {

    private static final Logger LOG = LoggerFactory.getLogger(FactIngestionService.class);
    private static final Set<String> AGREEMENT_STATUSES = Set.of("FORMALIZED", "IN_PAYMENT", "SETTLED", "BROKEN",
            "CANCELLED", "RENEGOTIATED");

    private final SettlementFactRepository facts;
    private final SettlementSources sources;
    private final SettlementLocks locks;
    private final SettlementMetrics metrics;

    FactIngestionService(SettlementFactRepository facts, SettlementSources sources, SettlementLocks locks,
                         SettlementMetrics metrics) {
        this.facts = facts;
        this.sources = sources;
        this.locks = locks;
        this.metrics = metrics;
    }

    void agreementAttributed(AgreementAttributionRecorded e) {
        locks.lockAgreement(e.agreementId());
        AgreementFact fact = facts.findAgreement(e.agreementId()).orElseGet(() -> new AgreementFact(e.agreementId(),
                e.creditorId(), e.portfolioId(), "FORMALIZED", Money.ofNullable(e.agreementAmount()), e.formalizedAt()));
        Integer installments = sources.agreementInstallmentCount(e.agreementId()).orElse(null);
        fact.formalized(e.portfolioId(), Money.ofNullable(e.agreementAmount()), e.daysPastDue(), installments,
                e.replacedAgreementId() != null, e.formalizedAt());
        facts.save(fact);
    }

    void agreementStatusChanged(AgreementStatusChanged e) {
        if (!AGREEMENT_STATUSES.contains(e.newStatus())) {
            metrics.eventIgnored("agreement_status", "unknown_status");
            return;
        }
        locks.lockAgreement(e.agreementId());
        Optional<AgreementFact> found = facts.findAgreement(e.agreementId());
        AgreementFact fact = found.orElseGet(() -> new AgreementFact(e.agreementId(), e.creditorId(), e.portfolioId(),
                e.newStatus(), Money.ofNullable(e.totalAmount()), e.occurredAt()));
        fact.statusChanged(e.newStatus(), e.occurredAt(), Money.ofNullable(e.totalAmount()), e.previousStatus() == null);
        facts.save(fact);
    }

    void legalExpenseChanged(LegalExpenseChanged e) {
        locks.lockExpense(e.expenseId());
        Optional<ReimbursableExpense> found = facts.findExpense(e.expenseId(), ReimbursableExpense.Kind.CHARGE);
        ReimbursableExpense charge = found.orElseGet(() -> {
            ReimbursableExpense created = facts.save(ReimbursableExpense.charge(e.expenseId(), e.matterId(),
                    e.creditorId(), e.type(), Money.of(e.amount()), e.incurredOn(), e.advancedBy(), e.reimbursable(),
                    e.hasReceipt()));
            metrics.expense(created.getKind().name(), created.getEffect().name());
            return created;
        });
        LocalDate statusDate = e.statusDate() != null ? e.statusDate()
                : e.occurredAt().atZone(TimeConfig.BUSINESS_ZONE).toLocalDate();
        switch (e.status()) {
            case "RECORDED" -> {
                // lançamento já materializado acima (reentrega ou ordem invertida não duplicam)
            }
            case "CANCELLED" -> cancel(charge, statusDate);
            case "REIMBURSED" -> reimbursedOutside(charge);
            default -> metrics.eventIgnored("legal_expense", "unknown_status");
        }
    }

    private void cancel(ReimbursableExpense charge, LocalDate on) {
        if (charge.getStatus() == ReimbursableExpense.Status.CANCELLED
                || facts.findExpense(charge.getLegalExpenseId(), ReimbursableExpense.Kind.CANCELLATION).isPresent()) {
            return;
        }
        if (charge.getStatementId() == null) {
            charge.cancelBeforeStatement();
            facts.save(charge);
            return;
        }
        ReimbursableExpense cancellation = facts.save(charge.cancellation(on));
        metrics.expense(cancellation.getKind().name(), cancellation.getEffect().name());
    }

    private void reimbursedOutside(ReimbursableExpense charge) {
        if (charge.getStatus() != ReimbursableExpense.Status.ACTIVE) {
            return;
        }
        if (charge.getStatementId() == null) {
            charge.reimbursedOutside();
            facts.save(charge);
            return;
        }
        if (charge.getEffect() == ReimbursableExpense.Effect.DEDUCT) {
            metrics.eventIgnored("legal_expense", "reimbursed_after_statement");
            LOG.atWarn().addKeyValue("legalExpenseId", charge.getLegalExpenseId())
                    .addKeyValue("statementId", charge.getStatementId())
                    .log("Despesa marcada como reembolsada no jurídico depois de deduzida em demonstrativo; "
                            + "verificar reembolso em duplicidade");
        }
    }
}
