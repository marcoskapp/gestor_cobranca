package br.com.gestorcobranca.payment.domain.model;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.DomainException;
import br.com.gestorcobranca.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentAllocationTest {

    private final Instant now = Instant.parse("2026-10-23T12:00:00Z");

    private PaymentAllocation agreement(String... amounts) {
        List<PaymentAllocation.Line> lines = java.util.Arrays.stream(amounts)
                .map(a -> PaymentAllocation.Line.installment(UUID.randomUUID(), Money.of(a))).toList();
        return PaymentAllocation.toAgreement(UUID.randomUUID(), AllocationOrigin.INITIAL, UUID.randomUUID(), null, null,
                lines, null);
    }

    @Test
    void agreementAllocationSumsLinesAndWaitsForTheNegotiationOutcome() {
        PaymentAllocation a = agreement("60.00", "40.00");
        assertThat(a.getAmount()).isEqualTo(Money.of("100.00"));
        assertThat(a.getOutcome()).isEqualTo(AllocationOutcome.PENDING);
        assertThat(a.availableForReallocation()).isEqualTo(Money.ZERO);
        assertThat(a.recordOutcome(Money.of("100.00"), Money.ZERO, now)).isTrue();
        assertThat(a.getOutcome()).isEqualTo(AllocationOutcome.APPLIED);
        // idempotente: a repetição do resultado não muda nada
        assertThat(a.recordOutcome(Money.ZERO, Money.of("100.00"), now)).isFalse();
        assertThat(a.getOutcome()).isEqualTo(AllocationOutcome.APPLIED);
    }

    @Test
    void unappliedValueIsReleasedOnceAndCompensationNeverExceedsTheAmount() {
        PaymentAllocation a = agreement("100.00");
        a.recordOutcome(Money.ZERO, Money.of("100.00"), now);
        assertThat(a.getOutcome()).isEqualTo(AllocationOutcome.UNAPPLIED);
        a.release(Money.of("70.00"));
        a.compensate(Money.of("70.00"), UUID.randomUUID());
        assertThat(a.availableForReallocation()).isEqualTo(Money.of("30.00"));
        assertThat(a.net()).isEqualTo(Money.of("30.00"));
        assertThatThrownBy(() -> a.release(Money.of("30.01"))).isInstanceOf(BusinessRuleException.class);
        // estorno: compensa só o que ainda não foi compensado
        assertThat(a.reverse(now)).isEqualTo(Money.of("30.00"));
        assertThatThrownBy(() -> a.reverse(now)).isInstanceOf(DomainException.class);
        assertThat(a.availableForReallocation()).isEqualTo(Money.ZERO);
    }

    @Test
    void partialOutcomeAndKindsWithoutNegotiation() {
        PaymentAllocation a = agreement("100.00");
        a.recordOutcome(Money.of("80.00"), Money.of("20.00"), now);
        assertThat(a.getOutcome()).isEqualTo(AllocationOutcome.PARTIAL);
        assertThat(a.availableForReallocation()).isEqualTo(Money.of("20.00"));
        PaymentAllocation titles = PaymentAllocation.toTitles(UUID.randomUUID(), AllocationOrigin.REALLOCATION, null,
                List.of(PaymentAllocation.Line.title(UUID.randomUUID(), Money.of("5.00"))), a.getId());
        assertThat(titles.getOutcome()).isEqualTo(AllocationOutcome.NOT_APPLICABLE);
        assertThat(titles.publishesEvents()).isTrue();
        assertThatThrownBy(() -> titles.recordOutcome(Money.ZERO, Money.ZERO, now)).isInstanceOf(DomainException.class);
        PaymentAllocation refund = PaymentAllocation.refund(UUID.randomUUID(), Money.of("5.00"), "TED-1", null);
        assertThat(refund.publishesEvents()).isFalse();
        assertThatThrownBy(() -> PaymentAllocation.refund(UUID.randomUUID(), Money.of("5.00"), " ", null))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> PaymentAllocation.toTitles(UUID.randomUUID(), AllocationOrigin.INITIAL, null,
                List.of(PaymentAllocation.Line.installment(UUID.randomUUID(), Money.of("1.00"))), null))
                .isInstanceOf(BusinessRuleException.class);
    }
}
