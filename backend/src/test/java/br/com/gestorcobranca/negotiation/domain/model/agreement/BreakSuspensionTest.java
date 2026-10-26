package br.com.gestorcobranca.negotiation.domain.model.agreement;

import br.com.gestorcobranca.negotiation.domain.service.BreakHoldRules;
import br.com.gestorcobranca.negotiation.domain.service.BreakHoldRules.Outcome;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Suspensão da quebra por pagamento em revisão (decisões 494–503): prazo máximo, carência depois da
 * recusa, uso único por parcela e a ordem das regras.
 */
class BreakSuspensionTest {

    private static final LocalDate D0 = AgreementTest.D0;
    private static final Instant T0 = AgreementTest.T0;

    private static BreakSuspension suspension(LocalDate startedOn, int maxDays) {
        Agreement a = AgreementTest.agreement(2, 5);
        LocalDate breakDay = D0.plusMonths(1).plusDays(6);
        AgreementInstallment trigger = a.breakTriggerInstallment(breakDay).orElseThrow();
        return new BreakSuspension(a, trigger, 1, Money.of("634.00"), startedOn, maxDays, T0);
    }

    @Test
    void triggerIsTheLowestNumberedInstallmentOverdueBeyondTolerance() {
        Agreement a = AgreementTest.agreement(2, 5);
        assertThat(a.breakTriggerInstallment(D0.plusDays(5))).isEmpty();
        assertThat(a.breakTriggerInstallment(D0.plusMonths(1).plusDays(6))).get()
                .extracting(AgreementInstallment::getNumber).isEqualTo(1);
    }

    @Test
    void deadlineIsInclusiveAndBreakComesBackTheDayAfter() {
        BreakSuspension s = suspension(D0, 7);
        assertThat(s.isActive()).isTrue();
        assertThat(s.getSuspendedUntil()).isEqualTo(D0.plusDays(7));
        assertThat(s.deadlinePassed(D0.plusDays(7))).isFalse();
        assertThat(s.deadlinePassed(D0.plusDays(8))).isTrue();
        s.endByDeadline(T0);
        assertThat(s.getEndReason()).isEqualTo(BreakSuspensionEndReason.DEADLINE);
        assertThat(s.getEndReason().blocksNewSuspension()).isTrue();
        assertThat(s.inGrace(D0.plusDays(8))).isFalse();
        // termina uma vez só
        assertThatThrownBy(() -> s.endByAgreementEnded(T0)).isInstanceOf(ConflictException.class);
    }

    @Test
    void rejectionGivesGraceFromTheRejectionDayNeverBeforeTheStart() {
        BreakSuspension s = suspension(D0, 7);
        s.endByReview(false, UUID.randomUUID(), D0.plusDays(2), 3, T0);
        assertThat(s.getEndReason()).isEqualTo(BreakSuspensionEndReason.REJECTED);
        assertThat(s.getGraceUntil()).isEqualTo(D0.plusDays(5));
        assertThat(s.inGrace(D0.plusDays(5))).isTrue();
        assertThat(s.inGrace(D0.plusDays(6))).isFalse();
        assertThat(s.durationDays(D0.plusDays(2))).isEqualTo(2);

        BreakSuspension early = suspension(D0, 7);
        early.endByReview(false, null, D0.minusDays(1), 0, T0);
        assertThat(early.getGraceUntil()).isEqualTo(D0);
        // carência 0: a quebra vale na próxima execução
        assertThat(early.inGrace(D0.plusDays(1))).isFalse();
    }

    @Test
    void approvalEndsWithoutGraceAndDoesNotBlockANewSuspension() {
        BreakSuspension s = suspension(D0, 7);
        s.endByReview(true, UUID.randomUUID(), D0.plusDays(1), 3, T0);
        assertThat(s.getEndReason()).isEqualTo(BreakSuspensionEndReason.APPROVED);
        assertThat(s.getGraceUntil()).isNull();
        assertThat(s.getEndReason().blocksNewSuspension()).isFalse();
        assertThat(BreakSuspensionEndReason.AGREEMENT_ENDED.blocksNewSuspension()).isFalse();
    }

    @Test
    void onlyAnActiveAgreementWithPendingPaymentIsSuspended() {
        Agreement a = AgreementTest.agreement(2, 5);
        AgreementInstallment trigger = a.getInstallments().getFirst();
        assertThatThrownBy(() -> new BreakSuspension(a, trigger, 0, Money.ZERO, D0, 7, T0))
                .hasMessageContaining("pagamento em revisão");
        a.cancel("Devedor desistiu", D0, T0);
        assertThatThrownBy(() -> new BreakSuspension(a, trigger, 1, Money.of("1.00"), D0, 7, T0))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void decisionOrder() {
        // suspensão em vigor segura a quebra
        assertThat(BreakHoldRules.decide(true, true, false, false, false)).isEqualTo(Outcome.HOLD_SUSPENDED);
        // pagamento confirmado ainda não baixado: espera
        assertThat(BreakHoldRules.decide(false, false, true, false, false)).isEqualTo(Outcome.DEFER_IN_TRANSIT);
        // carência depois da recusa
        assertThat(BreakHoldRules.decide(false, true, false, true, true)).isEqualTo(Outcome.HOLD_GRACE);
        // pendente: suspende, salvo parcela já usada
        assertThat(BreakHoldRules.decide(false, true, false, false, false)).isEqualTo(Outcome.SUSPEND);
        assertThat(BreakHoldRules.decide(false, true, false, false, true)).isEqualTo(Outcome.BREAK_SUSPENSION_USED);
        assertThat(BreakHoldRules.decide(false, false, false, false, false)).isEqualTo(Outcome.BREAK);
        assertThat(Outcome.BREAK_SUSPENSION_USED.allowsBreak()).isTrue();
        assertThat(Outcome.SUSPEND.allowsBreak()).isFalse();
        assertThat(Outcome.DEFER_IN_TRANSIT.allowsBreak()).isFalse();
    }

    @Test
    void reconcileEndsOnReviewDeadlineOrAgreementEnd() {
        assertThat(BreakHoldRules.reconcile(true, true, false, false)).isNull();
        assertThat(BreakHoldRules.reconcile(true, true, true, true)).isEqualTo(BreakSuspensionEndReason.DEADLINE);
        assertThat(BreakHoldRules.reconcile(true, false, true, true)).isEqualTo(BreakSuspensionEndReason.APPROVED);
        assertThat(BreakHoldRules.reconcile(true, false, false, false)).isEqualTo(BreakSuspensionEndReason.REJECTED);
        assertThat(BreakHoldRules.reconcile(false, true, false, false)).isEqualTo(BreakSuspensionEndReason.AGREEMENT_ENDED);
    }
}
