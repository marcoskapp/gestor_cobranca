package br.com.gestorcobranca.debt.domain.model;

import br.com.gestorcobranca.debt.domain.service.TitleLedger;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Regras da correção manual do valor (RB06): modo de autorização, segregação, validações e a carga posterior. */
class TitleAmountAdjustmentTest {

    private static final Instant T0 = Instant.parse("2026-11-05T12:00:00Z");
    private static final Money LIMIT = Money.of("200.00");
    private static final TitleAmountAdjustment.Evidence EVIDENCE =
            new TitleAmountAdjustment.Evidence("k", "e.pdf", "application/pdf", 10, "a".repeat(64));

    private DebtTitle title;
    private final List<TitleLedgerEntry> ledger = new ArrayList<>();
    private final UUID requester = UUID.randomUUID();

    @BeforeEach
    void open() {
        title = new DebtTitle(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, "T-1", 1,
                LocalDate.of(2026, 1, 10), Money.of("1000.00"), UUID.randomUUID());
        ledger.addAll(TitleLedger.open(title, T0, TitleLedgerEntry.REF_IMPORT_BATCH, UUID.randomUUID()).entries());
    }

    private Map<LedgerComponent, Money> balances() {
        Map<LedgerComponent, Money> b = new EnumMap<>(LedgerComponent.class);
        b.put(LedgerComponent.PRINCIPAL, title.getOpenBalance());
        return b;
    }

    private TitleAmountAdjustment request(String principal, TitleAmountAdjustment.Evidence evidence) {
        return new TitleAmountAdjustment(title, balances(), Map.of(LedgerComponent.PRINCIPAL, Money.of(principal)),
                AmountAdjustmentReason.CALCULATION_ERROR, "Erro de cálculo", null, evidence, LIMIT, Money.ZERO, requester,
                "Ana", T0);
    }

    @Test
    void smallReductionIsWithinTheLimitAndAnythingElseNeedsASecondPerson() {
        assertThat(request("800.00", null).getApprovalMode()).isEqualTo(AmountAdjustmentMode.WITHIN_LIMIT);
        assertThat(request("799.99", EVIDENCE).getApprovalMode()).isEqualTo(AmountAdjustmentMode.DUAL_CONTROL);
        assertThat(request("1000.01", EVIDENCE).getApprovalMode()).isEqualTo(AmountAdjustmentMode.DUAL_CONTROL);
    }

    private TitleAmountAdjustment requestMany(Map<LedgerComponent, Money> targets, TitleAmountAdjustment.Evidence evidence) {
        Map<LedgerComponent, Money> b = balances();
        b.put(LedgerComponent.FINE, Money.of("300.00"));
        b.put(LedgerComponent.HONORARIUM, Money.ZERO);
        return new TitleAmountAdjustment(title, b, targets, AmountAdjustmentReason.CALCULATION_ERROR, "Erro de cálculo",
                null, evidence, LIMIT, Money.ZERO, requester, "Ana", T0);
    }

    @Test
    void withinTheLimitOnlyForAPureReductionWhoseSumFitsTheLimit() {
        // principal 1.000 → 100 e honorários 0 → 899,99: líquido −0,01, mas desloca valor do credor para a assessoria
        assertThat(requestMany(Map.of(LedgerComponent.PRINCIPAL, Money.of("100.00"),
                LedgerComponent.HONORARIUM, Money.of("899.99")), EVIDENCE).getApprovalMode())
                .isEqualTo(AmountAdjustmentMode.DUAL_CONTROL);
        assertThatThrownBy(() -> requestMany(Map.of(LedgerComponent.PRINCIPAL, Money.of("100.00"),
                LedgerComponent.HONORARIUM, Money.of("899.99")), null)).hasMessageContaining("evidência");
        // aumento de um componente com redução maior em outro (líquido −100)
        assertThat(requestMany(Map.of(LedgerComponent.PRINCIPAL, Money.of("850.00"),
                LedgerComponent.FINE, Money.of("350.00")), EVIDENCE).getApprovalMode())
                .isEqualTo(AmountAdjustmentMode.DUAL_CONTROL);
        // duas reduções de 150: soma 300 acima do limite
        assertThat(requestMany(Map.of(LedgerComponent.PRINCIPAL, Money.of("850.00"),
                LedgerComponent.FINE, Money.of("150.00")), EVIDENCE).getApprovalMode())
                .isEqualTo(AmountAdjustmentMode.DUAL_CONTROL);
        // duas reduções que somam exatamente o limite
        assertThat(requestMany(Map.of(LedgerComponent.PRINCIPAL, Money.of("900.00"),
                LedgerComponent.FINE, Money.of("200.00")), null).getApprovalMode())
                .isEqualTo(AmountAdjustmentMode.WITHIN_LIMIT);
    }

    private TitleAmountAdjustment requestAfter(String recent, String principal, TitleAmountAdjustment.Evidence evidence) {
        return new TitleAmountAdjustment(title, balances(), Map.of(LedgerComponent.PRINCIPAL, Money.of(principal)),
                AmountAdjustmentReason.CALCULATION_ERROR, "Erro de cálculo", null, evidence, LIMIT, Money.of(recent),
                requester, "Ana", T0);
    }

    @Test
    void recentReductionsOnTheSameTitleCountTowardsTheLimit() {
        // limite 200: 150 já aplicados sem segunda pessoa na janela; 50 ainda cabem, 50,01 não
        assertThat(requestAfter("150.00", "950.00", null).getApprovalMode()).isEqualTo(AmountAdjustmentMode.WITHIN_LIMIT);
        TitleAmountAdjustment over = requestAfter("150.00", "949.99", EVIDENCE);
        assertThat(over.getApprovalMode()).isEqualTo(AmountAdjustmentMode.DUAL_CONTROL);
        assertThat(over.isCumulativeLimitReached()).isTrue();
        // sem evidência, a mensagem explica que foi o acumulado do título
        assertThatThrownBy(() -> requestAfter("150.00", "900.00", null)).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("já aplicadas neste título").hasMessageContaining("evidência");
        // acima do limite sozinha: não é o acumulado que manda
        TitleAmountAdjustment alone = requestAfter("150.00", "700.00", EVIDENCE);
        assertThat(alone.getApprovalMode()).isEqualTo(AmountAdjustmentMode.DUAL_CONTROL);
        assertThat(alone.isCumulativeLimitReached()).isFalse();
        assertThat(request("900.00", null).isCumulativeLimitReached()).isFalse();
        assertThat(TitleAmountAdjustment.isPureReductionWithin(Money.ZERO, Money.of("50.00"), Money.of("150.00"), LIMIT))
                .isTrue();
        assertThat(TitleAmountAdjustment.isPureReductionWithin(Money.ZERO, Money.of("0.01"), Money.of("200.00"), LIMIT))
                .isFalse();
    }

    @Test
    void approvalTriggerTellsWhyAndHowMuchThePendingRequestPassesTheLimit() {
        // limite 200: redução de 300 sozinha passa 100
        TitleAmountAdjustment alone = request("700.00", EVIDENCE);
        var trigger = alone.approvalTrigger(Money.of("999.00"));
        assertThat(trigger.reason()).isEqualTo(TitleAmountAdjustment.ApprovalReason.REDUCTION_ABOVE_LIMIT);
        assertThat(trigger.threshold()).as("o limite gravado no pedido, não o atual").isEqualTo(Money.of("200.00"));
        assertThat(trigger.decreases()).isEqualTo(Money.of("300.00"));
        assertThat(trigger.excess()).isEqualTo(Money.of("100.00"));
        // somada às recentes: 150 + 50,01 passa 0,01
        var cumulative = requestAfter("150.00", "949.99", EVIDENCE).approvalTrigger(Money.ZERO);
        assertThat(cumulative.reason()).isEqualTo(TitleAmountAdjustment.ApprovalReason.CUMULATIVE_ABOVE_LIMIT);
        assertThat(cumulative.recentReductions()).isEqualTo(Money.of("150.00"));
        assertThat(cumulative.excess()).isEqualTo(Money.of("0.01"));
        // aumento: sempre segunda pessoa
        var increase = requestMany(Map.of(LedgerComponent.FINE, Money.of("350.00")), EVIDENCE).approvalTrigger(Money.ZERO);
        assertThat(increase.reason()).isEqualTo(TitleAmountAdjustment.ApprovalReason.INCREASE);
        assertThat(increase.increases()).isEqualTo(Money.of("50.00"));
        // aplicada dentro do limite: sem motivo
        assertThat(request("800.00", null).approvalTrigger(Money.ZERO)).isNull();
    }

    @Test
    void dualControlNeedsEvidenceOrDisputeAndTheRequesterNeverApproves() {
        assertThatThrownBy(() -> request("500.00", null)).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("evidência");
        TitleAmountAdjustment a = request("500.00", EVIDENCE);
        assertThatThrownBy(() -> a.markApplied(requester, "Ana", null, T0)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> a.reject(requester, "Ana", "não", T0)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> a.cancel(UUID.randomUUID(), "Outro", null, T0)).isInstanceOf(ConflictException.class);
        a.markApplied(UUID.randomUUID(), "Bruno", "ok", T0);
        assertThat(a.getStatus()).isEqualTo(AmountAdjustmentStatus.APPLIED);
        assertThat(a.getDecidedByName()).isEqualTo("Bruno");
        assertThatThrownBy(() -> a.reject(UUID.randomUUID(), "Carla", "tarde", T0)).isInstanceOf(ConflictException.class);
    }

    @Test
    void invalidRequestsAreRefused() {
        assertThatThrownBy(() -> request("1000.00", null)).hasMessageContaining("iguais aos atuais");
        assertThatThrownBy(() -> request("0.00", EVIDENCE)).hasMessageContaining("cancele o título");
        assertThatThrownBy(() -> new TitleAmountAdjustment(title, balances(), Map.of(LedgerComponent.PRINCIPAL,
                Money.of("900.00")), AmountAdjustmentReason.OTHER, " ", null, null, LIMIT, Money.ZERO, requester, "Ana",
                T0))
                .hasMessageContaining("Descreva");
    }

    @Test
    void appliedCorrectionMakesThePrincipalManualAndLaterLoadsAreClassified() {
        TitleAmountAdjustment a = request("900.00", null);
        a.markApplied(requester, "Ana", null, T0);
        ledger.addAll(TitleLedger.adjustManually(title, ledger, a.deltas(), T0, "amount_adjustment", a.getId(), "x")
                .entries());
        assertThat(title.getPrincipalAmount()).isEqualTo(Money.of("900.00"));
        assertThat(title.getOpenBalance()).isEqualTo(Money.of("900.00"));
        assertThat(title.isManualPrincipal()).isTrue();

        assertThat(title.receiveCreditorPrincipal(Money.of("1000.00"))).isEqualTo(DebtTitle.CreditorPrincipal.DIVERGENT);
        title.acknowledgeCreditorPrincipal(Money.of("1000.00"));
        assertThat(title.receiveCreditorPrincipal(Money.of("1000.00"))).isEqualTo(DebtTitle.CreditorPrincipal.ACKNOWLEDGED);
        assertThat(title.receiveCreditorPrincipal(Money.of("950.00"))).isEqualTo(DebtTitle.CreditorPrincipal.DIVERGENT);
        assertThat(title.receiveCreditorPrincipal(Money.of("900.00"))).isEqualTo(DebtTitle.CreditorPrincipal.CONVERGED);
        assertThat(title.isManualPrincipal()).isFalse();
        assertThat(title.receiveCreditorPrincipal(Money.of("880.00"))).isEqualTo(DebtTitle.CreditorPrincipal.APPLY);
    }

    @Test
    void manualAdjustmentNeverTakesAComponentBelowZero() {
        assertThatThrownBy(() -> TitleLedger.adjustManually(title, ledger, Map.of(LedgerComponent.FINE, Money.of("-1.00")),
                T0, "amount_adjustment", UUID.randomUUID(), "x")).hasMessageContaining("negativo");
    }

    @Test
    void divergenceCountsRepeatsAndNeedsAReasonToBeDecided() {
        TitleAmountDivergence d = new TitleAmountDivergence(title, Money.of("1200.00"), UUID.randomUUID(), T0);
        d.seenAgain(UUID.randomUUID(), T0.plusSeconds(60));
        assertThat(d.getOccurrences()).isEqualTo(2);
        assertThatThrownBy(() -> d.close(AmountDivergenceStatus.KEPT_MANUAL, UUID.randomUUID(), "Ana", " ", T0))
                .hasMessageContaining("motivo");
        d.close(AmountDivergenceStatus.KEPT_MANUAL, UUID.randomUUID(), "Ana", "Credor confirmou", T0);
        assertThat(d.isOpen()).isFalse();
    }
}
