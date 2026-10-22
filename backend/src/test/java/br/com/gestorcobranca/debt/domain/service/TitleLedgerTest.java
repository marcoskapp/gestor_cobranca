package br.com.gestorcobranca.debt.domain.service;

import br.com.gestorcobranca.debt.domain.model.DebtTitle;
import br.com.gestorcobranca.debt.domain.model.LedgerComponent;
import br.com.gestorcobranca.debt.domain.model.LedgerEntryType;
import br.com.gestorcobranca.debt.domain.model.TitleLedgerEntry;
import br.com.gestorcobranca.debt.domain.model.TitleStatus;
import br.com.gestorcobranca.debt.domain.service.TitleLedger.Posting;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static br.com.gestorcobranca.debt.domain.model.LedgerComponent.FINE;
import static br.com.gestorcobranca.debt.domain.model.LedgerComponent.HONORARIUM;
import static br.com.gestorcobranca.debt.domain.model.LedgerComponent.INTEREST;
import static br.com.gestorcobranca.debt.domain.model.LedgerComponent.PRINCIPAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TitleLedgerTest {

    private static final Instant T0 = Instant.parse("2026-10-01T12:00:00Z");

    private DebtTitle title;
    /** Simula o razão persistido: o chamador real recarrega os lançamentos sob lock. */
    private final List<TitleLedgerEntry> ledger = new ArrayList<>();

    @BeforeEach
    void openTitle() {
        title = new DebtTitle(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, "T-1", 1,
                LocalDate.of(2026, 1, 10), Money.of("1000.00"), UUID.randomUUID());
        apply(TitleLedger.open(title, T0, TitleLedgerEntry.REF_IMPORT_BATCH, title.getImportBatchId()));
    }

    private Posting apply(Posting posting) {
        ledger.addAll(posting.entries());
        return posting;
    }

    private static Money m(String v) {
        return Money.of(v);
    }

    @Test
    void openingSetsBalanceWithoutSettling() {
        assertThat(title.getOpenBalance()).isEqualTo(m("1000.00"));
        assertThat(title.getStatus()).isEqualTo(TitleStatus.OPEN);
        assertThat(ledger).singleElement().satisfies(e -> {
            assertThat(e.getEntryType()).isEqualTo(LedgerEntryType.OPENING);
            assertThat(e.getComponent()).isEqualTo(PRINCIPAL);
            assertThat(e.getAmount()).isEqualTo(m("1000.00"));
        });
    }

    @Test
    void balanceIsBrokenDownByComponent() {
        UUID agreement = UUID.randomUUID();
        apply(TitleLedger.applyCharges(title, ledger, TitleLedgerEntry.REF_AGREEMENT, agreement,
                Map.of(INTEREST, m("150.00"), FINE, m("20.00"), HONORARIUM, m("117.00")), T0, null));
        apply(TitleLedger.applyDiscount(title, ledger, LedgerEntryType.DISCOUNT, agreement,
                Map.of(INTEREST, m("150.00"), FINE, m("20.00")), T0, "campanha"));
        apply(TitleLedger.applyPayment(title, ledger, UUID.randomUUID(), Map.of(PRINCIPAL, m("400.00"), HONORARIUM, m("40.00")),
                T0.plusSeconds(60)));

        TitleBalance balance = TitleBalance.of(ledger);
        assertThat(balance.balanceOf(PRINCIPAL)).isEqualTo(m("600.00"));
        assertThat(balance.balanceOf(INTEREST)).isEqualTo(Money.ZERO);
        assertThat(balance.balanceOf(HONORARIUM)).isEqualTo(m("77.00"));
        assertThat(balance.components().get(INTEREST).charged()).isEqualTo(m("150.00"));
        assertThat(balance.components().get(INTEREST).discounted()).isEqualTo(m("150.00"));
        assertThat(balance.components().get(PRINCIPAL).paid()).isEqualTo(m("400.00"));
        assertThat(balance.openBalance()).isEqualTo(m("677.00"));
        assertThat(balance.paidTotal()).isEqualTo(m("440.00"));
        assertThat(title.getOpenBalance()).isEqualTo(m("677.00"));
        assertThat(title.getPaidTotal()).isEqualTo(m("440.00"));
        assertThat(title.getLastPaymentAt()).isEqualTo(T0.plusSeconds(60));
    }

    @Test
    void partialPaymentReducesBalanceAndFullPaymentSettles() {
        UUID p1 = UUID.randomUUID();
        Posting first = apply(TitleLedger.applyPayment(title, ledger, p1, Map.of(PRINCIPAL, m("300.00")), T0));
        assertThat(first.applied()).isTrue();
        assertThat(first.previousBalance()).isEqualTo(m("1000.00"));
        assertThat(title.getOpenBalance()).isEqualTo(m("700.00"));
        assertThat(title.getStatus()).isEqualTo(TitleStatus.OPEN);

        apply(TitleLedger.applyPayment(title, ledger, UUID.randomUUID(), Map.of(PRINCIPAL, m("700.00")), T0));
        assertThat(title.getOpenBalance()).isEqualTo(Money.ZERO);
        assertThat(title.getStatus()).isEqualTo(TitleStatus.SETTLED);
    }

    @Test
    void repeatingTheSamePaymentIsIdempotent() {
        UUID payment = UUID.randomUUID();
        apply(TitleLedger.applyPayment(title, ledger, payment, Map.of(PRINCIPAL, m("300.00")), T0));
        Posting again = apply(TitleLedger.applyPayment(title, ledger, payment, Map.of(PRINCIPAL, m("300.00")), T0));
        assertThat(again.applied()).isFalse();
        assertThat(ledger).hasSize(2);
        assertThat(title.getOpenBalance()).isEqualTo(m("700.00"));
    }

    @Test
    void samePaymentWithDifferentAmountsIsConflict() {
        UUID payment = UUID.randomUUID();
        apply(TitleLedger.applyPayment(title, ledger, payment, Map.of(PRINCIPAL, m("300.00")), T0));
        assertThatThrownBy(() -> TitleLedger.applyPayment(title, ledger, payment, Map.of(PRINCIPAL, m("301.00")), T0))
                .isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("code", "ledger.reference_conflict");
    }

    @Test
    void paymentCannotExceedComponentBalance() {
        assertThatThrownBy(() -> TitleLedger.applyPayment(title, ledger, UUID.randomUUID(), Map.of(PRINCIPAL, m("1000.01")), T0))
                .isInstanceOf(BusinessRuleException.class).hasFieldOrPropertyWithValue("code", "ledger.amount_exceeds_balance");
        assertThatThrownBy(() -> TitleLedger.applyPayment(title, ledger, UUID.randomUUID(), Map.of(INTEREST, m("1.00")), T0))
                .hasFieldOrPropertyWithValue("code", "ledger.amount_exceeds_balance");
        assertThatThrownBy(() -> TitleLedger.applyPayment(title, ledger, UUID.randomUUID(), Map.of(PRINCIPAL, m("-1.00")), T0))
                .hasFieldOrPropertyWithValue("code", "ledger.part_amount_invalid");
    }

    @Test
    void reversalMirrorsPaymentKeepsOriginalAndReopensSettledTitle() {
        UUID payment = UUID.randomUUID();
        apply(TitleLedger.applyPayment(title, ledger, payment, Map.of(PRINCIPAL, m("1000.00")), T0));
        assertThat(title.getStatus()).isEqualTo(TitleStatus.SETTLED);

        Posting reversal = apply(TitleLedger.reversePayment(title, ledger, payment, T0.plusSeconds(3600), "chargeback"));
        assertThat(reversal.previousStatus()).isEqualTo(TitleStatus.SETTLED);
        assertThat(reversal.entries()).singleElement().satisfies(e -> {
            assertThat(e.getEntryType()).isEqualTo(LedgerEntryType.REVERSAL);
            assertThat(e.getAmount()).isEqualTo(m("1000.00"));
            assertThat(e.getReferenceId()).isEqualTo(payment);
        });
        assertThat(ledger).extracting(TitleLedgerEntry::getEntryType)
                .containsExactly(LedgerEntryType.OPENING, LedgerEntryType.PAYMENT, LedgerEntryType.REVERSAL);
        assertThat(title.getStatus()).isEqualTo(TitleStatus.OPEN);
        assertThat(title.getOpenBalance()).isEqualTo(m("1000.00"));
        assertThat(title.getPaidTotal()).isEqualTo(Money.ZERO);
        assertThat(title.getLastPaymentAt()).isNull();

        assertThat(TitleBalance.of(ledger).components().get(PRINCIPAL).reversed()).isEqualTo(m("1000.00"));
        assertThat(TitleLedger.reversePayment(title, ledger, payment, T0, "repetido").applied()).isFalse();
    }

    @Test
    void reversalOfUnknownPaymentIsRejected() {
        assertThatThrownBy(() -> TitleLedger.reversePayment(title, ledger, UUID.randomUUID(), T0, "x"))
                .hasFieldOrPropertyWithValue("code", "ledger.payment_not_found");
    }

    @Test
    void reversalIsAcceptedOnCancelledTitle() {
        UUID payment = UUID.randomUUID();
        apply(TitleLedger.applyPayment(title, ledger, payment, Map.of(PRINCIPAL, m("100.00")), T0));
        title.cancel("indevido");
        apply(TitleLedger.reversePayment(title, ledger, payment, T0, "devolução"));
        assertThat(title.getStatus()).isEqualTo(TitleStatus.CANCELLED);
        assertThat(title.getOpenBalance()).isEqualTo(m("1000.00"));
    }

    @Test
    void writeOffOfRemainingBalanceSettles() {
        UUID agreement = UUID.randomUUID();
        apply(TitleLedger.applyPayment(title, ledger, UUID.randomUUID(), Map.of(PRINCIPAL, m("800.00")), T0));
        apply(TitleLedger.applyDiscount(title, ledger, LedgerEntryType.WRITE_OFF, agreement, Map.of(PRINCIPAL, m("200.00")),
                T0, "quitação do acordo"));
        assertThat(title.getStatus()).isEqualTo(TitleStatus.SETTLED);
        assertThat(TitleBalance.of(ledger).components().get(PRINCIPAL).discounted()).isEqualTo(m("200.00"));
    }

    @Test
    void settledAndClosedTitlesRejectNewCredits() {
        apply(TitleLedger.applyPayment(title, ledger, UUID.randomUUID(), Map.of(PRINCIPAL, m("1000.00")), T0));
        assertThatThrownBy(() -> TitleLedger.applyCharges(title, ledger, "agreement", UUID.randomUUID(),
                Map.of(INTEREST, m("1.00")), T0, null)).hasFieldOrPropertyWithValue("code", "title.not_accepting_postings");
    }

    @Test
    void chargesCannotTargetPrincipal() {
        assertThatThrownBy(() -> TitleLedger.applyCharges(title, ledger, "agreement", UUID.randomUUID(),
                Map.of(PRINCIPAL, m("1.00")), T0, null)).hasFieldOrPropertyWithValue("code", "ledger.charge_principal_not_allowed");
    }

    @Test
    void principalAdjustmentPostsDifferenceAndIsIdempotentPerBatch() {
        UUID batch = UUID.randomUUID();
        Posting up = apply(TitleLedger.adjustPrincipal(title, ledger, m("1100.00"), T0, TitleLedgerEntry.REF_IMPORT_BATCH,
                batch, "correção"));
        assertThat(up.entries()).singleElement().extracting(TitleLedgerEntry::getAmount).isEqualTo(m("100.00"));
        assertThat(title.getPrincipalAmount()).isEqualTo(m("1100.00"));
        assertThat(title.getOpenBalance()).isEqualTo(m("1100.00"));

        assertThat(TitleLedger.adjustPrincipal(title, ledger, m("1100.00"), T0, TitleLedgerEntry.REF_IMPORT_BATCH, batch,
                "correção").applied()).isFalse();

        apply(TitleLedger.applyPayment(title, ledger, UUID.randomUUID(), Map.of(PRINCIPAL, m("600.00")), T0));
        assertThatThrownBy(() -> TitleLedger.adjustPrincipal(title, ledger, m("500.00"), T0,
                TitleLedgerEntry.REF_IMPORT_BATCH, UUID.randomUUID(), "x"))
                .hasFieldOrPropertyWithValue("code", "title.principal_below_settled");
    }

    @Test
    void entrySignMustMatchType() {
        assertThatThrownBy(() -> new TitleLedgerEntry(title.getId(), LedgerEntryType.PAYMENT, LedgerComponent.PRINCIPAL,
                m("10.00"), T0, "payment", UUID.randomUUID(), null))
                .hasFieldOrPropertyWithValue("code", "ledger.amount_sign_invalid");
    }
}
