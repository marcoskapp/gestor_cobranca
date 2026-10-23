package br.com.gestorcobranca.collection.domain.model;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CollectionCaseTest {

    static final Instant T0 = Instant.parse("2026-10-23T12:00:00Z");

    static CaseBalance balance(String open, int titles) {
        return new CaseBalance(Money.of(open), Money.of(open), titles, 0, 0, LocalDate.of(2026, 6, 1));
    }

    static CollectionCase newCase() {
        return new CollectionCase(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), balance("1000.00", 2), T0);
    }

    @Test
    void opensOnlyWithOpenTitlesAndStartsUnowned() {
        CollectionCase c = newCase();
        assertThat(c.getStatus()).isEqualTo(CaseStatus.OPEN);
        assertThat(c.getOwnerType()).isEqualTo(OwnerType.NONE);
        assertThat(c.daysOverdue(LocalDate.of(2026, 6, 11))).isEqualTo(10);
        assertThatThrownBy(() -> new CollectionCase(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                CaseBalance.EMPTY, T0)).isInstanceOf(BusinessRuleException.class).hasMessageContaining("títulos");
    }

    @Test
    void stagesMoveFreelyAndHoldsKeepTheStageToRestore() {
        CollectionCase c = newCase();
        assertThat(c.moveToStage(CaseStatus.IN_NEGOTIATION, "contato")).isTrue();
        assertThat(c.moveToStage(CaseStatus.IN_NEGOTIATION, "de novo")).isFalse();
        assertThat(c.enterHold(CaseStatus.DISPUTED, "contestação")).isTrue();
        // estágio alterado durante a retenção só muda o estágio guardado
        assertThat(c.moveToStage(CaseStatus.AGREEMENT, "acordo")).isFalse();
        assertThat(c.getStatus()).isEqualTo(CaseStatus.DISPUTED);
        assertThat(c.getResumeStatus()).isEqualTo(CaseStatus.AGREEMENT);
        assertThat(c.releaseHold(CaseStatus.DISPUTED, false, "resolvida")).isTrue();
        assertThat(c.getStatus()).isEqualTo(CaseStatus.AGREEMENT);
        assertThat(c.getResumeStatus()).isNull();
    }

    @Test
    void higherPriorityHoldWinsAndReleasingItFallsBackToDisputeWhenStillBlocking() {
        CollectionCase c = newCase();
        c.enterHold(CaseStatus.DISPUTED, "contestação");
        assertThat(c.enterHold(CaseStatus.LEGAL, "jurídico")).isTrue();
        assertThat(c.enterHold(CaseStatus.SUSPENDED, "suspensão")).isFalse();
        assertThat(c.getStatus()).isEqualTo(CaseStatus.LEGAL);
        assertThat(c.releaseHold(CaseStatus.SUSPENDED, true, "x")).isFalse();
        assertThat(c.releaseHold(CaseStatus.LEGAL, true, "retorno do jurídico")).isTrue();
        assertThat(c.getStatus()).isEqualTo(CaseStatus.DISPUTED);
        assertThat(c.releaseHold(CaseStatus.DISPUTED, false, "resolvida")).isTrue();
        assertThat(c.getStatus()).isEqualTo(CaseStatus.OPEN);
    }

    @Test
    void closedCaseIsFinalAndLosesOwnerQueueAndEligibility() {
        CollectionCase c = newCase();
        c.routeTo(UUID.randomUUID());
        c.assignToUser(UUID.randomUUID(), null, T0);
        c.applyEligibility(Eligibility.of(Set.of(), EnumSet.of(InteractionChannel.PHONE)), T0);
        c.close(CloseReason.SETTLED, "quitado", T0);
        assertThat(c.getStatus()).isEqualTo(CaseStatus.CLOSED);
        assertThat(c.getOwnerType()).isEqualTo(OwnerType.NONE);
        assertThat(c.getAssigneeUserId()).isNull();
        assertThat(c.getQueueId()).isNull();
        assertThat(c.isEligible()).isFalse();
        assertThat(c.eligibility().reasons()).containsExactly(EligibilityReason.CASE_CLOSED);
        assertThatThrownBy(() -> c.moveToStage(CaseStatus.IN_NEGOTIATION, "x")).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> c.enterHold(CaseStatus.SUSPENDED, "x")).isInstanceOf(ConflictException.class);
    }

    @Test
    void ownerFollowsCommunicationSemantics() {
        CollectionCase c = newCase();
        UUID queue = UUID.randomUUID();
        c.routeTo(queue);
        assertThat(c.getOwnerType()).isEqualTo(OwnerType.QUEUE);
        UUID user = UUID.randomUUID();
        c.assignToUser(user, null, T0);
        assertThat(c.getOwnerType()).isEqualTo(OwnerType.USER);
        assertThat(c.isAssignedTo(user)).isTrue();
        c.handToAssistant(T0);
        assertThat(c.getOwnerType()).isEqualTo(OwnerType.ASSISTANT);
        assertThat(c.getAssigneeUserId()).isNull();
        c.release(T0);
        assertThat(c.getOwnerType()).isEqualTo(OwnerType.QUEUE);
        c.routeTo(null);
        assertThat(c.getOwnerType()).isEqualTo(OwnerType.NONE);
    }

    @Test
    void assignableBalanceIsZeroWhileIneligible() {
        CollectionCase c = new CollectionCase(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new CaseBalance(Money.of("900.00"), Money.of("600.00"), 2, 1, 1, LocalDate.of(2026, 1, 1)), T0);
        c.applyEligibility(Eligibility.of(EnumSet.of(EligibilityReason.CONTACT_RESTRICTED), Set.of()), T0);
        assertThat(c.assignableBalance()).isEqualTo(Money.ZERO);
        c.applyEligibility(Eligibility.of(Set.of(), EnumSet.of(InteractionChannel.EMAIL)), T0);
        assertThat(c.assignableBalance()).isEqualTo(Money.of("600.00"));
        assertThat(c.eligibility().contactableChannels()).containsExactly(InteractionChannel.EMAIL);
    }

    @Test
    void interactionCountersTrackLatestContact() {
        CollectionCase c = newCase();
        c.registerInteraction(T0, false);
        c.registerInteraction(T0.plusSeconds(60), true);
        c.registerInteraction(T0.minusSeconds(60), false);
        assertThat(c.getInteractionCount()).isEqualTo(3);
        assertThat(c.getEffectiveContactCount()).isEqualTo(1);
        assertThat(c.getLastInteractionAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(c.getLastEffectiveContactAt()).isEqualTo(T0.plusSeconds(60));
    }

    @Test
    void balanceRejectsEligibleAboveOpen() {
        assertThatThrownBy(() -> new CaseBalance(Money.of("10.00"), Money.of("11.00"), 1, 0, 0, null))
                .isInstanceOf(BusinessRuleException.class);
    }
}
