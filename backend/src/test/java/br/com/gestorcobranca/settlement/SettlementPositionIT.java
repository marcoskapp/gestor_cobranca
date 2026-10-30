package br.com.gestorcobranca.settlement;

import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.AgreementView;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.InstallmentView;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.ExternalPaymentCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.PaymentView;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.TargetInput;
import br.com.gestorcobranca.settlement.application.port.in.CommissionRuleUseCases.RuleSetCommand;
import br.com.gestorcobranca.settlement.application.port.in.CommissionRuleUseCases.RuleSetView;
import br.com.gestorcobranca.settlement.application.port.in.SettlementPositionUseCases;
import br.com.gestorcobranca.settlement.application.port.in.SettlementPositionUseCases.PositionView;
import br.com.gestorcobranca.settlement.application.port.in.SettlementStatementUseCases.CreateStatementCommand;
import br.com.gestorcobranca.settlement.application.port.in.SettlementStatementUseCases.StatementView;
import br.com.gestorcobranca.settlement.application.port.in.SettlementStatementUseCases.TotalsView;
import br.com.gestorcobranca.settlement.domain.model.rules.CommissionRules;
import br.com.gestorcobranca.settlement.domain.model.rules.CommissionRules.HonorariumPolicy;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Posição do repasse em tempo real: simulação do demonstrativo sem gravar nada, com o mesmo valor que o demonstrativo
 * calcularia se fechado agora; cache curto invalidado por alteração; escopo por credor.
 */
class SettlementPositionIT extends SettlementTestSupport {

    @Autowired
    SettlementPositionUseCases positions;

    @Test
    void positionMatchesTheStatementCalculatedForTheSamePeriodWithoutWritingAnything() {
        provision("posicao");
        RuleSetView draft = asFinance1(() -> ruleSets.createDraft(new RuleSetCommand(contractA,
                new CommissionRules(new BigDecimal("10"), List.of(), new BigDecimal("20.00"),
                        HonorariumPolicy.agencyFull(), false), "Contrato A")));
        asFinance2(() -> ruleSets.publish(draft.id(), LocalDate.of(2026, 1, 1)));

        // nada recebido: posição zerada, sem demonstrativo
        PositionView empty = asFinance1(() -> positions.position(creditorA));
        assertThat(empty.totals().netTransfer()).isEqualByComparingTo("0.00");
        assertThat(empty.direction()).isEqualTo("NONE");
        assertThat(empty.asOf()).isEqualTo(today);

        AgreementView agreement = formalizeAgreement(2);
        InstallmentView first = agreement.installments().getFirst();
        BigDecimal amount = first.amount();
        Instant paidAt = Instant.now().minus(3, ChronoUnit.DAYS);
        PaymentView informed = asFinance1(() -> payments.registerExternal(new ExternalPaymentCommand(creditorA, debtor,
                amount, paidAt, "TRANSFER", "AGENCY_ACCOUNT", "TED-P1", null,
                new TargetInput("AGREEMENT", agreement.id(), List.of(first.id()), null)), pdf(), null));
        if ("INFORMED".equals(informed.status())) {
            asFinance2(() -> payments.approve(informed.id(), "Comprovante conferido"));
        }
        UUID allocation = asFinance1(() -> payments.get(informed.id())).allocations().getFirst().id();
        awaitTrue("recebimento apurado", () -> count("select count(*) from settlement.receipt where allocation_id = ? "
                + "and kind = 'PAYMENT' and rating_status = 'RATED'", allocation) == 1);

        // posição agora: inclui o recebimento, sem vincular nem criar demonstrativo
        PositionView position = asFinance1(() -> positions.position(creditorA));
        assertThat(position.cached()).isFalse();
        assertThat(position.totals().receipts()).isEqualTo(1);
        assertThat(position.totals().recovered()).isEqualByComparingTo(amount);
        assertThat(position.totals().netTransfer()).isPositive();
        assertThat(position.direction()).isEqualTo("AGENCY_TO_CREDITOR");
        assertThat(position.daily()).isNotEmpty();
        assertThat(position.daily().getLast().netTransfer()).isEqualByComparingTo(position.totals().netTransfer());
        assertThat(position.daily().getLast().recovered()).isEqualByComparingTo(amount);
        assertThat(count("select count(*) from settlement.receipt where creditor_id = ? and statement_id is not null",
                creditorA)).isZero();
        assertThat(count("select count(*) from settlement.statement where creditor_id = ?", creditorA)).isZero();

        // segunda leitura sem alteração: cache curto
        assertThat(asFinance1(() -> positions.position(creditorA)).cached()).isTrue();
        // escopo por credor
        assertThatThrownBy(() -> asFinanceB(() -> positions.position(creditorA))).isNotNull();

        // amanhã: o demonstrativo do mesmo período (até ontem = hoje da posição) calcula o mesmo valor
        clock.shift(Duration.ofDays(1));
        LocalDate start = position.periodStart();
        StatementView created = asFinance1(() -> statements.create(new CreateStatementCommand(creditorA, start, today)));
        StatementView calculated = asFinance1(() -> statements.calculate(created.id()));
        assertSameTotals(position.totals(), calculated.totals());

        // com o demonstrativo em aberto, a posição o recalcula como simulação: mesmo valor, nada desvinculado
        PositionView withOpen = asFinance1(() -> positions.position(creditorA));
        assertThat(withOpen.cached()).isFalse();
        assertThat(withOpen.openStatementId()).isEqualTo(created.id());
        assertThat(withOpen.openStatementStatus()).isEqualTo("CALCULATED");
        assertSameTotals(withOpen.totals(), calculated.totals());
        assertThat(count("select count(*) from settlement.receipt where statement_id = ?", created.id())).isEqualTo(1);
    }

    @Test
    void positionRequiresSettlementView() {
        provision("posicao-perm");
        assertThatThrownBy(() -> asOperator(() -> positions.position(creditorA)))
                .isInstanceOf(ScopeAccessDeniedException.class);
        assertThat(asManager(() -> positions.sentToCreditor(creditorA))).isEmpty();
    }

    private static void assertSameTotals(TotalsView position, TotalsView statement) {
        assertThat(position.receipts()).isEqualTo(statement.receipts());
        assertThat(position.recovered()).isEqualByComparingTo(statement.recovered());
        assertThat(position.receivedByAgency()).isEqualByComparingTo(statement.receivedByAgency());
        assertThat(position.receivedByCreditor()).isEqualByComparingTo(statement.receivedByCreditor());
        assertThat(position.agencyHonorarium()).isEqualByComparingTo(statement.agencyHonorarium());
        assertThat(position.commission()).isEqualByComparingTo(statement.commission());
        assertThat(position.fixedFees()).isEqualByComparingTo(statement.fixedFees());
        assertThat(position.expenses()).isEqualByComparingTo(statement.expenses());
        assertThat(position.adjustments()).isEqualByComparingTo(statement.adjustments());
        assertThat(position.netTransfer()).isEqualByComparingTo(statement.netTransfer());
        assertThat(position.pendingReconciliation()).isEqualByComparingTo(statement.pendingReconciliation());
        assertThat(position.pendingRules()).isEqualByComparingTo(statement.pendingRules());
    }
}
