package br.com.gestorcobranca.debt.domain.model;

import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.DomainException;
import br.com.gestorcobranca.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Aumento do limite com confirmação de outra pessoa, no valor que ela revisou (decisão da correção de valor). */
class AmountAdjustmentSettingsTest {

    private static final Duration VALIDITY = Duration.ofDays(15);
    private final UUID proposer = UUID.randomUUID();
    private final UUID reviewer = UUID.randomUUID();

    @Test
    void confirmationAppliesOnlyTheReviewedProposal() {
        AmountAdjustmentSettings settings = new AmountAdjustmentSettings(Money.of(1000), proposer, "Gestora", Instant.now());
        assertThat(settings.hasProposal()).isTrue();
        // quem propôs troca o valor depois de a outra pessoa abrir a tela
        settings.change(Money.of(100_000), proposer, "Gestora", Instant.now());

        assertThatThrownBy(() -> settings.confirmProposal(reviewer, Money.of(1000), Instant.now(), VALIDITY))
                .isInstanceOf(ConflictException.class).hasMessageContaining("mudou");
        assertThatThrownBy(() -> settings.confirmProposal(reviewer, null, Instant.now(), VALIDITY)).isInstanceOf(ConflictException.class);
        assertThat(settings.getApprovalThreshold()).isEqualTo(AmountAdjustmentSettings.DEFAULT_THRESHOLD);

        settings.confirmProposal(reviewer, Money.of("100000.00"), Instant.now(), VALIDITY);
        assertThat(settings.getApprovalThreshold().compareTo(Money.of(100_000))).isZero();
        assertThat(settings.hasProposal()).isFalse();
    }

    @Test
    void proposerNeverConfirms() {
        AmountAdjustmentSettings settings = new AmountAdjustmentSettings(Money.of(1000), proposer, "Gestora", Instant.now());
        assertThatThrownBy(() -> settings.confirmProposal(proposer, Money.of(1000), Instant.now(), VALIDITY))
                .isInstanceOf(ConflictException.class).hasMessageContaining("Quem propôs");
    }

    @Test
    void proposalExpiresAfterTheValidityAndIsDiscardedWithoutChangingTheLimit() {
        Instant proposedAt = Instant.parse("2026-11-01T12:00:00Z");
        AmountAdjustmentSettings settings = new AmountAdjustmentSettings(Money.of(1000), proposer, "Gestora", proposedAt);
        assertThat(settings.proposalExpiresAt(VALIDITY)).isEqualTo(Instant.parse("2026-11-16T12:00:00Z"));
        Instant lastMoment = proposedAt.plus(VALIDITY).minusMillis(1);
        Instant expiry = proposedAt.plus(VALIDITY);
        assertThat(settings.isProposalExpired(lastMoment, VALIDITY)).isFalse();
        assertThat(settings.discardExpiredProposal(lastMoment, VALIDITY)).isFalse();
        assertThat(settings.isProposalExpired(expiry, VALIDITY)).isTrue();

        assertThatThrownBy(() -> settings.confirmProposal(reviewer, Money.of(1000), expiry, VALIDITY))
                .isInstanceOf(ConflictException.class).hasMessageContaining("venceu")
                .satisfies(e -> assertThat(((DomainException) e).code()).isEqualTo("title_amount.proposal_expired"));
        assertThat(settings.getApprovalThreshold()).isEqualTo(AmountAdjustmentSettings.DEFAULT_THRESHOLD);

        assertThat(settings.discardExpiredProposal(expiry, VALIDITY)).isTrue();
        assertThat(settings.hasProposal()).isFalse();
        assertThat(settings.proposalExpiresAt(VALIDITY)).isNull();
        assertThat(settings.getApprovalThreshold()).isEqualTo(AmountAdjustmentSettings.DEFAULT_THRESHOLD);
        assertThat(settings.discardExpiredProposal(expiry, VALIDITY)).isFalse();
    }

    @Test
    void proposalStillValidIsConfirmedOnItsLastDay() {
        Instant proposedAt = Instant.parse("2026-11-01T12:00:00Z");
        AmountAdjustmentSettings settings = new AmountAdjustmentSettings(Money.of(1000), proposer, "Gestora", proposedAt);
        settings.confirmProposal(reviewer, Money.of(1000), proposedAt.plus(Duration.ofDays(14)), VALIDITY);
        assertThat(settings.getApprovalThreshold().compareTo(Money.of(1000))).isZero();
    }
}
