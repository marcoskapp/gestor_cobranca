package br.com.gestorcobranca.settlement.domain.model;

import br.com.gestorcobranca.settlement.domain.model.SettlementStatement.Totals;
import br.com.gestorcobranca.settlement.domain.model.rules.PaymentType;
import br.com.gestorcobranca.settlement.domain.model.rules.ReceivedBy;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.DomainException;
import br.com.gestorcobranca.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementLifecycleTest {

    private static final UUID CREDITOR = UUID.randomUUID();
    private static final UUID CALCULATOR = UUID.randomUUID();
    private static final UUID APPROVER = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-23T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 23);

    private static Totals net(String value) {
        Totals z = Totals.zero();
        return new Totals(z.receivedAgency(), z.receivedCreditor(), z.reversed(), z.recovered(), z.reconciled(),
                z.agencyHonorarium(), z.creditorHonorarium(), z.commission(), z.fixedFee(), z.expenses(), z.adjustments(),
                Money.of(value), z.negotiated(), z.forecastCommission(), z.pendingReconciliation(), z.pendingRules(), 0, 1);
    }

    private static SettlementStatement calculated(String netValue) {
        SettlementStatement s = new SettlementStatement(CREDITOR, 1, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        s.calculated(net(netValue), "{}", CALCULATOR, NOW, TODAY);
        return s;
    }

    @Test
    void fullCycleWithSegregatedApproval() {
        SettlementStatement s = calculated("855.00");
        assertThat(s.getStatus()).isEqualTo(StatementStatus.CALCULATED);
        s.review(CALCULATOR, NOW);
        assertThatThrownBy(() -> s.approve(CALCULATOR, NOW, Set.of()))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("calculou");
        s.approve(APPROVER, NOW, Set.of());
        assertThat(s.getStatus()).isEqualTo(StatementStatus.APPROVED);
        assertThatThrownBy(() -> s.calculated(net("1.00"), "{}", CALCULATOR, NOW, TODAY))
                .isInstanceOf(DomainException.class);
        s.send(CALCULATOR, "EMAIL", "msg-1", NOW);
        s.contest(CALCULATOR, "Credor não reconhece o recebimento X", NOW);
        assertThat(s.getStatus()).isEqualTo(StatementStatus.CONTESTED);
        s.acknowledge(CALCULATOR, "Corrigido no demonstrativo seguinte", NOW);
        assertThat(s.getStatus()).isEqualTo(StatementStatus.ACKNOWLEDGED);
    }

    @Test
    void adjustmentRequesterCannotApproveAndAdjustmentResetsReview() {
        SettlementStatement s = calculated("100.00");
        s.review(CALCULATOR, NOW);
        s.adjustmentsChanged(Money.of("-30.00"));
        assertThat(s.getStatus()).isEqualTo(StatementStatus.CALCULATED);
        assertThat(s.getNetTransferAmount()).isEqualTo(Money.of("70.00"));
        s.adjustmentsChanged(Money.of("-10.00"));
        assertThat(s.getNetTransferAmount()).isEqualTo(Money.of("90.00"));
        s.review(CALCULATOR, NOW);
        assertThatThrownBy(() -> s.approve(APPROVER, NOW, Set.of(APPROVER))).hasMessageContaining("ajuste");
    }

    @Test
    void periodMustHaveEndedToCalculate() {
        SettlementStatement s = new SettlementStatement(CREDITOR, 1, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31));
        assertThatThrownBy(() -> s.calculated(net("0.00"), "{}", CALCULATOR, NOW, TODAY))
                .hasMessageContaining("encerrado");
    }

    @Test
    void secondApprovalOfAdjustmentByAnotherPerson() {
        SettlementAdjustment small = new SettlementAdjustment(UUID.randomUUID(), SettlementAdjustment.Category.OTHER,
                Money.of("-50.00"), "Tarifa bancária do repasse", null, false, CALCULATOR, NOW);
        assertThat(small.getStatus()).isEqualTo(SettlementAdjustment.Status.APPLIED);
        SettlementAdjustment big = new SettlementAdjustment(UUID.randomUUID(),
                SettlementAdjustment.Category.COMMISSION_CORRECTION, Money.of("2500.00"),
                "Comissão cobrada a maior no período anterior", null, true, CALCULATOR, NOW);
        assertThat(big.getStatus()).isEqualTo(SettlementAdjustment.Status.PENDING_SECOND_APPROVAL);
        assertThatThrownBy(() -> big.approve(CALCULATOR, null, NOW)).hasMessageContaining("outra pessoa");
        big.approve(APPROVER, "ok", NOW);
        assertThat(big.getStatus()).isEqualTo(SettlementAdjustment.Status.APPLIED);
        assertThatThrownBy(() -> new SettlementAdjustment(UUID.randomUUID(),
                SettlementAdjustment.Category.PRIOR_PERIOD_CORRECTION, Money.of("1.00"), "correção sem referência",
                null, false, CALCULATOR, NOW)).hasMessageContaining("demonstrativo corrigido");
    }

    @Test
    void transferLimitedToOutstandingAndDirectionFollowsTheSign() {
        SettlementStatement positive = calculated("855.00");
        positive.review(CALCULATOR, NOW);
        positive.approve(APPROVER, NOW, Set.of());
        CreditorTransfer.Evidence evidence = new CreditorTransfer.Evidence("k", "c.pdf", "application/pdf", 10, "ab");
        CreditorTransfer partial = new CreditorTransfer(positive, Money.of("500.00"), TODAY, TODAY, "TED 1", null,
                evidence, CALCULATOR);
        assertThat(partial.getDirection()).isEqualTo(CreditorTransfer.Direction.AGENCY_TO_CREDITOR);
        assertThat(positive.outstanding()).isEqualTo(Money.of("355.00"));
        assertThatThrownBy(() -> new CreditorTransfer(positive, Money.of("355.01"), TODAY, TODAY, "TED 2", null,
                evidence, CALCULATOR)).hasMessageContaining("saldo");
        partial.cancel(positive, APPROVER, "Lançado em duplicidade", NOW);
        assertThat(positive.outstanding()).isEqualTo(Money.of("855.00"));

        SettlementStatement negative = calculated("-205.00");
        negative.review(CALCULATOR, NOW);
        negative.approve(APPROVER, NOW, Set.of());
        CreditorTransfer fromCreditor = new CreditorTransfer(negative, Money.of("205.00"), TODAY, TODAY, "PIX 9", null,
                evidence, CALCULATOR);
        assertThat(fromCreditor.getDirection()).isEqualTo(CreditorTransfer.Direction.CREDITOR_TO_AGENCY);
        assertThat(negative.outstanding()).isEqualTo(Money.ZERO);
    }

    @Test
    void transferRequiresApprovedStatement() {
        SettlementStatement open = calculated("100.00");
        CreditorTransfer.Evidence evidence = new CreditorTransfer.Evidence("k", "c.pdf", "application/pdf", 10, "ab");
        assertThatThrownBy(() -> new CreditorTransfer(open, Money.of("100.00"), TODAY, TODAY, "TED", null, evidence,
                CALCULATOR)).hasMessageContaining("aprovado");
    }

    @Test
    void ruleSetPublisherMustDifferFromEditor() {
        CommissionRuleSet draft = new CommissionRuleSet(CREDITOR, UUID.randomUUID(), 1, "{}", 1, null, CALCULATOR, NOW);
        assertThatThrownBy(() -> draft.publish(TODAY, CALCULATOR, NOW)).hasMessageContaining("conferência");
        draft.publish(TODAY, APPROVER, NOW);
        assertThat(draft.covers(TODAY)).isTrue();
        assertThat(draft.covers(TODAY.minusDays(1))).isFalse();
        assertThatThrownBy(() -> draft.updateDraft("{}", 1, null, APPROVER, NOW)).isInstanceOf(DomainException.class);
        draft.endOn(TODAY.plusDays(9));
        assertThat(draft.covers(TODAY.plusDays(10))).isFalse();
    }

    @Test
    void receiptReversalIsNegativeAndInheritsTheProfile() {
        Receipt payment = Receipt.payment(new Receipt.PaymentData(UUID.randomUUID(), UUID.randomUUID(), null, "T-1",
                CREDITOR, UUID.randomUUID(), null, UUID.randomUUID(), UUID.randomUUID(), Money.of("1100.00"),
                Money.of("100.00"), 120, PaymentType.INSTALLMENTS, ReceivedBy.AGENCY, false, "PIX", "PROVIDER",
                LocalDate.of(2026, 9, 15), "{}"));
        Receipt reversal = payment.reversal(Money.of("550.00"), Money.of("50.00"), UUID.randomUUID(), "CHARGEBACK",
                LocalDate.of(2026, 10, 2));
        assertThat(reversal.getAmount()).isEqualTo(Money.of("-550.00"));
        assertThat(reversal.getHonorariumAmount()).isEqualTo(Money.of("-50.00"));
        assertThat(reversal.getCompetenceDate()).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(reversal.getPaidOn()).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(reversal.profile()).isEqualTo(payment.profile());
        assertThatThrownBy(() -> payment.reversal(Money.of("1100.01"), Money.ZERO, UUID.randomUUID(), "X", TODAY))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> payment.bindTo(UUID.randomUUID())).hasMessageContaining("não apurado");
    }

    @Test
    void legalExpenseEffect() {
        ReimbursableExpense agency = ReimbursableExpense.charge(UUID.randomUUID(), UUID.randomUUID(), CREDITOR,
                "COURT_FEES", Money.of("300.00"), TODAY, "AGENCY", true, true);
        assertThat(agency.deduction()).isEqualTo(Money.of("300.00"));
        ReimbursableExpense creditorPaid = ReimbursableExpense.charge(UUID.randomUUID(), UUID.randomUUID(), CREDITOR,
                "COURT_FEES", Money.of("300.00"), TODAY, "CREDITOR", true, true);
        assertThat(creditorPaid.getEffect()).isEqualTo(ReimbursableExpense.Effect.INFORMATIVE);
        assertThat(creditorPaid.deduction()).isEqualTo(Money.ZERO);
        agency.bindTo(UUID.randomUUID());
        ReimbursableExpense cancellation = agency.cancellation(TODAY.plusDays(3));
        assertThat(cancellation.deduction()).isEqualTo(Money.of("-300.00"));
        assertThatThrownBy(agency::cancelBeforeStatement).hasMessageContaining("incluída");
        assertThat(List.of(cancellation.getKind())).containsExactly(ReimbursableExpense.Kind.CANCELLATION);
    }
}
