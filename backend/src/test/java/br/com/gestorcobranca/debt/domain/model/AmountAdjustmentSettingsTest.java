package br.com.gestorcobranca.debt.domain.model;

import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Aumento do limite com confirmação de outra pessoa, no valor que ela revisou (decisão da correção de valor). */
class AmountAdjustmentSettingsTest {

    private final UUID proposer = UUID.randomUUID();
    private final UUID reviewer = UUID.randomUUID();

    @Test
    void confirmationAppliesOnlyTheReviewedProposal() {
        AmountAdjustmentSettings settings = new AmountAdjustmentSettings(Money.of(1000), proposer, "Gestora", Instant.now());
        assertThat(settings.hasProposal()).isTrue();
        // quem propôs troca o valor depois de a outra pessoa abrir a tela
        settings.change(Money.of(100_000), proposer, "Gestora", Instant.now());

        assertThatThrownBy(() -> settings.confirmProposal(reviewer, Money.of(1000)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("mudou");
        assertThatThrownBy(() -> settings.confirmProposal(reviewer, null)).isInstanceOf(ConflictException.class);
        assertThat(settings.getApprovalThreshold()).isEqualTo(AmountAdjustmentSettings.DEFAULT_THRESHOLD);

        settings.confirmProposal(reviewer, Money.of("100000.00"));
        assertThat(settings.getApprovalThreshold().compareTo(Money.of(100_000))).isZero();
        assertThat(settings.hasProposal()).isFalse();
    }

    @Test
    void proposerNeverConfirms() {
        AmountAdjustmentSettings settings = new AmountAdjustmentSettings(Money.of(1000), proposer, "Gestora", Instant.now());
        assertThatThrownBy(() -> settings.confirmProposal(proposer, Money.of(1000)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("Quem propôs");
    }
}
