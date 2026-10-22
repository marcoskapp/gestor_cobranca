package br.com.gestorcobranca.debt.domain.model;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DebtTitleTest {

    static DebtTitle title(String principal, LocalDate dueDate) {
        return new DebtTitle(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, " CTR-1/01 ", 1, dueDate,
                Money.of(principal), UUID.randomUUID());
    }

    @Test
    void manualLifecycleFollowsAllowedTransitions() {
        DebtTitle t = title("100.00", LocalDate.of(2026, 1, 10));
        assertThat(t.getExternalId()).isEqualTo("CTR-1/01");
        assertThat(t.getStatus()).isEqualTo(TitleStatus.OPEN);

        assertThat(t.suspend("pedido do credor")).isEqualTo(TitleStatus.OPEN);
        assertThat(t.getStatusReason()).isEqualTo("pedido do credor");
        assertThat(t.reactivate()).isEqualTo(TitleStatus.SUSPENDED);
        assertThat(t.getStatusReason()).isNull();
        t.returnToCreditor("fim da vigência");
        assertThat(t.getStatus()).isEqualTo(TitleStatus.RETURNED);

        assertThatThrownBy(t::reactivate).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> t.cancel("x")).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "title.invalid_transition");
    }

    @Test
    void finalStatusesHaveNoTransitions() {
        assertThat(TitleStatus.CANCELLED.allowedTransitions()).isEmpty();
        assertThat(TitleStatus.RETURNED.allowedTransitions()).isEmpty();
        assertThat(TitleStatus.SETTLED.allowedTransitions()).containsExactly(TitleStatus.OPEN);
    }

    @Test
    void settledCannotBeSuspendedOrCancelledManually() {
        DebtTitle t = title("50.00", LocalDate.of(2026, 1, 10));
        t.recordPosting(LedgerEntryType.PAYMENT, Money.ZERO, Money.of("50.00"), null);
        assertThat(t.getStatus()).isEqualTo(TitleStatus.SETTLED);
        assertThatThrownBy(() -> t.suspend("x")).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> t.cancel("x")).isInstanceOf(ConflictException.class);
    }

    @Test
    void manualChangesRequireReason() {
        DebtTitle t = title("100.00", LocalDate.of(2026, 1, 10));
        assertThatThrownBy(() -> t.suspend(" ")).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> t.cancel(null)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> t.returnToCreditor("")).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void closedTitlesRejectPostingsButAcceptReversals() {
        DebtTitle t = title("100.00", LocalDate.of(2026, 1, 10));
        t.cancel("lançamento indevido");
        assertThatThrownBy(() -> t.requireAcceptsPosting(LedgerEntryType.PAYMENT))
                .isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("code", "title.not_accepting_postings");
        t.requireAcceptsPosting(LedgerEntryType.REVERSAL);
    }

    @Test
    void daysOverdueOnlyForTitlesInCollection() {
        DebtTitle t = title("100.00", LocalDate.of(2026, 1, 10));
        assertThat(t.daysOverdue(LocalDate.of(2026, 1, 10))).isZero();
        assertThat(t.daysOverdue(LocalDate.of(2026, 1, 5))).isZero();
        assertThat(t.daysOverdue(LocalDate.of(2026, 2, 9))).isEqualTo(30);
        t.suspend("análise");
        assertThat(t.daysOverdue(LocalDate.of(2026, 2, 9))).isEqualTo(30);
        t.cancel("indevido");
        assertThat(t.daysOverdue(LocalDate.of(2026, 2, 9))).isZero();
    }

    @Test
    void disputeLinkIsIdempotentAndOnlyClearedByTheSameDispute() {
        DebtTitle t = title("100.00", LocalDate.of(2026, 1, 10));
        UUID dispute = UUID.randomUUID();
        assertThat(t.markDisputed(dispute, true)).isTrue();
        assertThat(t.markDisputed(dispute, true)).isFalse();
        assertThat(t.clearDispute(UUID.randomUUID())).isFalse();
        assertThat(t.isDisputeBlocked()).isTrue();
        assertThat(t.clearDispute(dispute)).isTrue();
        assertThat(t.isDisputeBlocked()).isFalse();
        assertThat(t.getDisputeId()).isNull();
    }

    @Test
    void transferRequiresDifferentPortfolioAndOpenTitle() {
        DebtTitle t = title("100.00", LocalDate.of(2026, 1, 10));
        assertThatThrownBy(() -> t.transferTo(t.getPortfolioId())).isInstanceOf(BusinessRuleException.class);
        UUID target = UUID.randomUUID();
        t.transferTo(target);
        assertThat(t.getPortfolioId()).isEqualTo(target);
        t.returnToCreditor("devolução");
        assertThatThrownBy(() -> t.transferTo(UUID.randomUUID())).isInstanceOf(ConflictException.class);
    }

    @Test
    void rejectsInvalidData() {
        assertThatThrownBy(() -> title("0", LocalDate.now())).isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "title.principal_invalid");
        assertThatThrownBy(() -> new DebtTitle(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, " ", null,
                LocalDate.now(), Money.of(1), null)).hasFieldOrPropertyWithValue("code", "title.external_id_required");
        assertThatThrownBy(() -> new DebtTitle(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, "X", 0,
                LocalDate.now(), Money.of(1), null)).hasFieldOrPropertyWithValue("code", "title.installment_invalid");
    }
}
