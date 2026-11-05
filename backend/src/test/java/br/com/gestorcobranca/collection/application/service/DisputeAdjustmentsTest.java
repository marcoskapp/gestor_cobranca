package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.domain.model.CaseBalance;
import br.com.gestorcobranca.collection.domain.model.CollectionCase;
import br.com.gestorcobranca.collection.domain.model.DeadlineSource;
import br.com.gestorcobranca.collection.domain.model.Dispute;
import br.com.gestorcobranca.collection.domain.model.DisputeAdjustmentAction;
import br.com.gestorcobranca.collection.domain.model.DisputeOrigin;
import br.com.gestorcobranca.collection.domain.model.DisputeOutcome;
import br.com.gestorcobranca.collection.domain.model.DisputeType;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases.TitleView;
import br.com.gestorcobranca.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Detecção do ajuste feito no título a partir da situação e do saldo atual dos títulos da contestação. */
class DisputeAdjustmentsTest {

    static final Instant T0 = Instant.parse("2026-11-05T12:00:00Z");
    static final UUID TITLE = UUID.randomUUID();

    static Dispute resolved(DisputeAdjustmentAction action) {
        CollectionCase c = new CollectionCase(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new CaseBalance(Money.of("300.00"), Money.of("300.00"), 1, 0, 0, LocalDate.of(2026, 6, 1)), T0);
        Dispute d = new Dispute(c, Set.of(TITLE), DisputeType.WRONG_AMOUNT, DisputeOrigin.OPERATOR, "x", true,
                Money.of("300.00"), LocalDate.of(2026, 11, 15), LocalDate.of(2026, 11, 12), DeadlineSource.TENANT_DEFAULT, T0);
        d.resolve(DisputeOutcome.UPHELD, "ok", UUID.randomUUID(), T0, action, Money.of("300.00"));
        return d;
    }

    static Map<UUID, TitleView> title(String status, String balance) {
        return Map.of(TITLE, new TitleView(TITLE, null, null, null, null, "T-1", 1, LocalDate.of(2026, 6, 1),
                new BigDecimal("300.00"), new BigDecimal(balance), BigDecimal.ZERO, status, null, false, null, null, 0,
                null, 0, T0));
    }

    @Test
    void cancelIsDoneWhenNoTitleRemainsInCollection() {
        Dispute d = resolved(DisputeAdjustmentAction.CANCEL_TITLES);
        assertThat(DisputeAdjustments.effectVisible(d, title("OPEN", "300.00"))).isFalse();
        assertThat(DisputeAdjustments.effectVisible(d, title("OPEN", "100.00"))).isFalse();
        assertThat(DisputeAdjustments.effectVisible(d, title("CANCELLED", "300.00"))).isTrue();
        assertThat(DisputeAdjustments.effectVisible(d, Map.of())).isFalse();
    }

    @Test
    void amountAdjustmentOrPaymentIsDoneWhenTheBalanceDrops() {
        Dispute d = resolved(DisputeAdjustmentAction.ADJUST_AMOUNT);
        assertThat(DisputeAdjustments.effectVisible(d, title("OPEN", "300.00"))).isFalse();
        assertThat(DisputeAdjustments.effectVisible(d, title("OPEN", "180.00"))).isTrue();
        Dispute paid = resolved(DisputeAdjustmentAction.SETTLE_BY_PAYMENT);
        assertThat(DisputeAdjustments.effectVisible(paid, title("SETTLED", "0.00"))).isTrue();
    }

    @Test
    void resolutionActionComesFromTheTypeUnlessTheAnalystChoosesAnother() {
        Dispute d = resolved(DisputeAdjustmentAction.ADJUST_AMOUNT);
        assertThat(DisputeAdjustments.actionFor(d, DisputeOutcome.UPHELD, null)).isEqualTo(DisputeAdjustmentAction.ADJUST_AMOUNT);
        assertThat(DisputeAdjustments.actionFor(d, DisputeOutcome.UPHELD, "NONE")).isNull();
        assertThat(DisputeAdjustments.actionFor(d, DisputeOutcome.UPHELD, "CANCEL_TITLES"))
                .isEqualTo(DisputeAdjustmentAction.CANCEL_TITLES);
        assertThat(DisputeAdjustments.actionFor(d, DisputeOutcome.REJECTED, null)).isNull();
    }

    @Test
    void textsUseBrazilianFormatsAndOnlyTheFirstName() {
        assertThat(DisputeTexts.money(new BigDecimal("1234.5"))).isEqualTo("R$ 1.234,50");
        assertThat(DisputeTexts.firstName("MARIA da silva")).isEqualTo("Maria");
        assertThat(DisputeTexts.protocol(UUID.fromString("0a1b2c3d-0000-0000-0000-000000000000"))).isEqualTo("0A1B2C3D");
        assertThat(DisputeEvidenceLinkService.uniqueName(new java.util.HashSet<>(Set.of("a.pdf")), 2, "a.pdf"))
                .isEqualTo("2-a.pdf");
        assertThat(DisputeEvidenceLinkService.uniqueName(new java.util.HashSet<>(), 1, "../x/y.pdf")).isEqualTo(".._x_y.pdf");
    }
}
