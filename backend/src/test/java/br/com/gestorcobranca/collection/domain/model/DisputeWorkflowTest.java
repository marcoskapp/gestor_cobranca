package br.com.gestorcobranca.collection.domain.model;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Ciclo da contestação com responsável, credor, resposta ao devedor e ajuste sugerido. */
class DisputeWorkflowTest {

    static final Instant T0 = Instant.parse("2026-11-05T12:00:00Z");
    static final LocalDate TODAY = LocalDate.of(2026, 11, 5);

    static Dispute dispute(DisputeOrigin origin) {
        CollectionCase c = new CollectionCase(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new CaseBalance(Money.of("500.00"), Money.of("500.00"), 1, 0, 0, LocalDate.of(2026, 6, 1)), T0);
        return new Dispute(c, Set.of(UUID.randomUUID()), DisputeType.ALREADY_PAID, origin, "Pagou no caixa", true,
                Money.of("500.00"), TODAY.plusDays(10), TODAY.plusDays(12), DeadlineSource.TYPE, null, T0);
    }

    @Test
    void internalDeadlineIsCappedAndAssignmentChangesAreExplicit() {
        Dispute d = dispute(DisputeOrigin.OPERATOR);
        assertThat(d.getInternalDueDate()).isEqualTo(TODAY.plusDays(10));
        assertThat(d.getDeadlineSource()).isEqualTo(DeadlineSource.TYPE);
        UUID analyst = UUID.randomUUID();
        assertThat(d.assign(analyst, T0)).isNull();
        assertThat(d.isAssignedTo(analyst)).isTrue();
        assertThatThrownBy(() -> d.assign(analyst, T0)).isInstanceOf(ConflictException.class);
        assertThat(d.assign(null, T0)).isEqualTo(analyst);
        assertThat(d.getAssignedAt()).isNull();
    }

    @Test
    void creditorRequestMovesToReviewAndResolutionClearsTheWait() {
        Dispute d = dispute(DisputeOrigin.DEBTOR);
        d.awaitCreditor(T0);
        assertThat(d.getStatus()).isEqualTo(DisputeStatus.UNDER_REVIEW);
        assertThat(d.getAwaitingCreditorSince()).isEqualTo(T0);
        d.awaitCreditor(T0.plusSeconds(60));
        assertThat(d.getAwaitingCreditorSince()).isEqualTo(T0);
        d.resolve(DisputeOutcome.UPHELD, "Comprovante confere", UUID.randomUUID(), T0.plusSeconds(3600),
                DisputeAdjustmentAction.SETTLE_BY_PAYMENT, Money.of("500.00"));
        assertThat(d.getAwaitingCreditorSince()).isNull();
        assertThat(d.getDebtorNoticeStatus()).isEqualTo(DebtorNoticeStatus.PENDING);
        assertThat(d.getAdjustmentStatus()).isEqualTo(DisputeAdjustmentStatus.PENDING);
        assertThat(d.getAdjustmentBaseline()).isEqualTo(Money.of("500.00"));
        d.markDebtorNotified();
        assertThat(d.getDebtorNoticeStatus()).isEqualTo(DebtorNoticeStatus.SENT);
    }

    @Test
    void adjustmentNeedsReasonToBeDismissedAndRejectedNeverSuggestsOne() {
        Dispute d = dispute(DisputeOrigin.OPERATOR);
        d.resolve(DisputeOutcome.UPHELD, "ok", UUID.randomUUID(), T0, DisputeAdjustmentAction.CANCEL_TITLES,
                Money.of("500.00"));
        assertThatThrownBy(() -> d.closeAdjustment(DisputeAdjustmentStatus.DISMISSED, " ", UUID.randomUUID(), T0))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(d.closeAdjustment(DisputeAdjustmentStatus.DISMISSED, "Credor reemitiu a cobrança", UUID.randomUUID(),
                T0)).isTrue();
        assertThat(d.closeAdjustment(DisputeAdjustmentStatus.DONE, null, null, T0)).isFalse();

        Dispute rejected = dispute(DisputeOrigin.OPERATOR);
        assertThatThrownBy(() -> rejected.resolve(DisputeOutcome.REJECTED, "não procede", UUID.randomUUID(), T0,
                DisputeAdjustmentAction.CANCEL_TITLES, Money.ZERO)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void debtorResponseIsExpectedOnlyWhenTheDebtorContested() {
        Dispute creditor = dispute(DisputeOrigin.CREDITOR);
        creditor.resolve(DisputeOutcome.REJECTED, "ok", UUID.randomUUID(), T0);
        assertThat(creditor.getDebtorNoticeStatus()).isEqualTo(DebtorNoticeStatus.NOT_REQUIRED);
        Dispute withdrawn = dispute(DisputeOrigin.DEBTOR);
        withdrawn.withdraw("desistiu", UUID.randomUUID(), T0);
        assertThat(withdrawn.getDebtorNoticeStatus()).isEqualTo(DebtorNoticeStatus.NOT_REQUIRED);
        Dispute open = dispute(DisputeOrigin.DEBTOR);
        assertThatThrownBy(open::markDebtorNotified).isInstanceOf(ConflictException.class);
    }

    @Test
    void creditorRequestFollowUpHappensOncePerStep() {
        Dispute d = dispute(DisputeOrigin.OPERATOR);
        UUID evidence = UUID.randomUUID();
        DisputeCreditorRequest r = new DisputeCreditorRequest(d, 1, "ALREADY_PAID", 0, List.of("a@credor.test"),
                List.of("b@credor.test"), "analista@assessoria.test", "Assunto", "Texto", TODAY.plusDays(5),
                d.getTitleIds(), Set.of(evidence), T0.plusSeconds(7 * 86400), UUID.randomUUID());
        assertThat(r.getTo()).containsExactly("a@credor.test");
        assertThat(r.getCc()).containsExactly("b@credor.test");
        assertThat(r.queueReminder(T0)).isFalse(); // ainda na fila de envio
        r.markSent(T0);
        assertThat(r.queueReminder(T0)).isTrue();
        assertThat(r.queueReminder(T0)).isFalse();
        assertThat(r.flagOverdue(TODAY.plusDays(5), T0)).isFalse();
        assertThat(r.flagOverdue(TODAY.plusDays(6), T0)).isTrue();
        assertThat(r.flagOverdue(TODAY.plusDays(7), T0)).isFalse();
        assertThat(r.linkUsableAt(T0.plusSeconds(86400))).isTrue();
        assertThat(r.linkUsableAt(T0.plusSeconds(8 * 86400))).isFalse();
        assertThat(r.markAnswered(T0)).isTrue();
        assertThat(r.cancel()).isFalse();
        assertThatThrownBy(() -> new DisputeCreditorRequest(d, 2, "ALREADY_PAID", 0, List.of(), List.of(), null, "a", "b",
                TODAY.plusDays(5), Set.of(), Set.of(), null, UUID.randomUUID())).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void debtorNoticeStatusNeverRegresses() {
        DisputeDebtorNotice n = new DisputeDebtorNotice(UUID.randomUUID(), DebtorNoticeChannel.EMAIL, UUID.randomUUID(),
                DebtorNoticeDelivery.QUEUED, null, "UPHELD", 0, "Assunto", "Texto", UUID.randomUUID(), T0);
        assertThat(n.updateFromMessage("SENT", null, null, T0)).isTrue();
        assertThat(n.isDelivered()).isTrue();
        assertThat(n.updateFromMessage("READ", null, null, T0)).isTrue();
        assertThat(n.updateFromMessage("DELIVERED", null, null, T0)).isFalse();
        DisputeDebtorNotice blocked = new DisputeDebtorNotice(UUID.randomUUID(), DebtorNoticeChannel.WHATSAPP,
                UUID.randomUUID(), DebtorNoticeDelivery.QUEUED, null, null, null, null, "Texto", UUID.randomUUID(), T0);
        assertThat(blocked.updateFromMessage("FAILED", "CONTACT_DENIED", "RESTRICTED_CHANNEL", T0)).isTrue();
        assertThat(blocked.getStatus()).isEqualTo(DebtorNoticeDelivery.BLOCKED);
        assertThat(blocked.getFailureDetail()).isEqualTo("RESTRICTED_CHANNEL");
        assertThatThrownBy(() -> new DisputeDebtorNotice(UUID.randomUUID(), DebtorNoticeChannel.OTHER, UUID.randomUUID(),
                DebtorNoticeDelivery.REGISTERED, null, null, null, null, "x", UUID.randomUUID(), T0))
                .isInstanceOf(BusinessRuleException.class);
    }
}
