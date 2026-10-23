package br.com.gestorcobranca.collection.domain.model;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentPromiseTest {

    static final Instant NOW = Instant.parse("2026-10-23T15:00:00Z");
    static final LocalDate TODAY = LocalDate.of(2026, 10, 23);

    static PaymentPromise promise(String amount, LocalDate due, int tolerance) {
        return new PaymentPromise(CollectionCaseTest.newCase(), Money.of(amount), due, tolerance, 30, TODAY, null, null, NOW);
    }

    @Test
    void validatesAmountAndDates() {
        CollectionCase c = CollectionCaseTest.newCase();
        assertThatThrownBy(() -> new PaymentPromise(c, Money.of("1000.01"), TODAY, 2, 30, TODAY, null, null, NOW))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("saldo");
        assertThatThrownBy(() -> new PaymentPromise(c, Money.of("10.00"), TODAY.minusDays(1), 2, 30, TODAY, null, null, NOW))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> new PaymentPromise(c, Money.of("10.00"), TODAY.plusDays(31), 2, 30, TODAY, null, null, NOW))
                .extracting("code").isEqualTo("promise.due_too_far");
        assertThat(promise("100.00", TODAY.plusDays(5), 2).getBreaksAfter()).isEqualTo(TODAY.plusDays(7));
    }

    @Test
    void keptWhenConfirmedPaymentsReachTheAmountIdempotentByPayment() {
        PaymentPromise p = promise("300.00", TODAY.plusDays(3), 2);
        UUID first = UUID.randomUUID();
        assertThat(p.registerPayment(first, Money.of("100.00"), NOW.plusSeconds(10), NOW)).isFalse();
        assertThat(p.registerPayment(first, Money.of("100.00"), NOW.plusSeconds(10), NOW)).isFalse();
        assertThat(p.getPaidAmount()).isEqualTo(Money.of("100.00"));
        assertThat(p.registerPayment(UUID.randomUUID(), Money.of("200.00"), NOW.plusSeconds(20), NOW)).isTrue();
        assertThat(p.getStatus()).isEqualTo(PromiseStatus.KEPT);
    }

    @Test
    void paymentsBeforeThePromiseDoNotCount() {
        PaymentPromise p = promise("100.00", TODAY.plusDays(3), 0);
        assertThat(p.registerPayment(UUID.randomUUID(), Money.of("100.00"), NOW.minusSeconds(1), NOW)).isFalse();
        assertThat(p.getStatus()).isEqualTo(PromiseStatus.PENDING);
    }

    @Test
    void brokenOnlyAfterTheToleranceAndPartialIsReported() {
        PaymentPromise p = promise("100.00", TODAY.plusDays(1), 2);
        p.registerPayment(UUID.randomUUID(), Money.of("40.00"), NOW.plusSeconds(1), NOW);
        assertThat(p.breakIfOverdue(TODAY.plusDays(3), NOW)).isFalse();
        assertThat(p.breakIfOverdue(TODAY.plusDays(4), NOW)).isTrue();
        assertThat(p.getStatus()).isEqualTo(PromiseStatus.BROKEN);
        assertThat(p.getResolutionReason()).contains("parcial");
        assertThatThrownBy(() -> p.cancel("x", NOW)).isInstanceOf(ConflictException.class);
    }

    @Test
    void reversalReopensAKeptPromise() {
        PaymentPromise p = promise("100.00", TODAY.plusDays(1), 0);
        UUID payment = UUID.randomUUID();
        p.registerPayment(payment, Money.of("100.00"), NOW.plusSeconds(1), NOW);
        assertThat(p.reversePayment(UUID.randomUUID())).isFalse();
        assertThat(p.reversePayment(payment)).isTrue();
        assertThat(p.getStatus()).isEqualTo(PromiseStatus.PENDING);
        assertThat(p.getPaidAmount()).isEqualTo(Money.ZERO);
    }
}
