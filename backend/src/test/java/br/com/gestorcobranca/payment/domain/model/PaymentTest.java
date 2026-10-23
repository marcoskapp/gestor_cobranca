package br.com.gestorcobranca.payment.domain.model;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.DomainException;
import br.com.gestorcobranca.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {

    private final UUID creditor = UUID.randomUUID();
    private final UUID debtor = UUID.randomUUID();
    private final UUID informer = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-23T12:00:00Z");
    private final Payment.Evidence evidence = new Payment.Evidence("k", "c.pdf", "application/pdf", 10, "a".repeat(64));

    private Payment informed(boolean review) {
        return Payment.informed(PaymentMethod.TRANSFER, creditor, debtor, Money.of("900.00"), now.minusSeconds(3600),
                ExternalOrigin.AGENCY_ACCOUNT, "DOC", null, null, evidence, informer, review, now);
    }

    @Test
    void lowRiskExternalIsConfirmedOnRegistrationAndHighRiskWaitsForAnotherPerson() {
        Payment low = informed(false);
        assertThat(low.getStatus()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(low.getReviewMode()).isEqualTo(ReviewMode.AUTO_LOW_RISK);
        Payment high = informed(true);
        assertThat(high.getStatus()).isEqualTo(PaymentStatus.INFORMED);
        assertThatThrownBy(() -> high.approve(informer, null, now)).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("não pode revisá-lo");
        UUID reviewer = UUID.randomUUID();
        high.approve(reviewer, "ok", now);
        assertThat(high.getStatus()).isEqualTo(PaymentStatus.CONFIRMED);
        assertThat(high.getReviewedBy()).isEqualTo(reviewer);
        assertThatThrownBy(() -> high.approve(reviewer, null, now)).isInstanceOf(DomainException.class);
    }

    @Test
    void evidenceIsMandatoryAndPaymentDateCannotBeFuture() {
        assertThatThrownBy(() -> Payment.informed(PaymentMethod.CASH, creditor, debtor, Money.of("1.00"), now,
                ExternalOrigin.OTHER, null, null, null, null, informer, false, now)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> Payment.informed(PaymentMethod.CASH, creditor, debtor, Money.of("1.00"),
                now.plusSeconds(60), ExternalOrigin.OTHER, null, null, null, evidence, informer, false, now))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void rejectionRequiresReasonAndEndsTheCycle() {
        Payment p = informed(true);
        assertThatThrownBy(() -> p.reject(UUID.randomUUID(), " ", now)).isInstanceOf(BusinessRuleException.class);
        p.reject(UUID.randomUUID(), "Comprovante ilegível", now);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.REJECTED);
        assertThatThrownBy(() -> p.reverse(now)).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> p.reconcile(ReconciliationMode.STATEMENT, null, null, now)).isInstanceOf(DomainException.class);
    }

    @Test
    void reconcileOnceManualNeverByTheInformerAndReverseKeepsHistory() {
        Payment p = informed(false);
        assertThatThrownBy(() -> p.reconcile(ReconciliationMode.MANUAL, informer, null, now))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(p.reconcile(ReconciliationMode.MANUAL, UUID.randomUUID(), null, now)).isTrue();
        assertThat(p.reconcile(ReconciliationMode.STATEMENT, null, null, now)).isFalse();
        assertThat(p.reverse(now)).isEqualTo(PaymentStatus.RECONCILED);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.REVERSED);
        assertThat(p.getReconciledAt()).isEqualTo(now);
        assertThatThrownBy(() -> p.reverse(now)).isInstanceOf(DomainException.class);
    }

    @Test
    void allocationNeverExceedsTheAmountAndReleaseReturnsToBalance() {
        Payment p = Payment.fromProvider(PaymentMethod.PIX, creditor, debtor, Money.of("100.00"), now, null, "E2E",
                ReviewMode.PROVIDER, now);
        p.allocate(Money.of("60.00"));
        assertThat(p.unallocated()).isEqualTo(Money.of("40.00"));
        assertThatThrownBy(() -> p.allocate(Money.of("40.01"))).isInstanceOf(DomainException.class);
        p.release(Money.of("10.00"));
        assertThat(p.unallocated()).isEqualTo(Money.of("50.00"));
        assertThatThrownBy(() -> p.release(Money.of("60.00"))).isInstanceOf(BusinessRuleException.class);
        assertThat(p.attachProviderTransaction("E2E")).isFalse();
        assertThatThrownBy(() -> p.attachProviderTransaction("OUTRO")).isInstanceOf(DomainException.class);
        assertThatThrownBy(() -> Payment.fromProvider(PaymentMethod.PIX, creditor, debtor, Money.of("1.00"), now, null,
                null, ReviewMode.DUAL_CONTROL, now)).isInstanceOf(BusinessRuleException.class);
    }
}
