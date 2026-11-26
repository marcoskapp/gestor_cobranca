package br.com.gestorcobranca.debt.domain.model;

import br.com.gestorcobranca.debt.domain.model.ReversalHold.ItemKind;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Regras da retenção da cobrança após estorno de pagamento recebido pelo credor (H07 parte 3; RS-109, RS-115, RS-116). */
class ReversalHoldTest {

    private static final Instant NOW = Instant.parse("2026-11-26T13:00:00Z");
    private static final UUID REVERSER = UUID.randomUUID();
    private static final UUID INFORMER = UUID.randomUUID();
    private static final UUID REVIEWER = UUID.randomUUID();
    private static final UUID TITLE = UUID.randomUUID();

    private static ReversalHold hold(String placedByType, UUID informedBy) {
        return new ReversalHold(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), List.of(),
                "OTHER", List.of(TITLE), List.of(), List.of(UUID.randomUUID()),
                "USER".equals(placedByType) ? REVERSER : new UUID(0, 0), placedByType, informedBy, NOW);
    }

    @Test
    void startsPendingWithTheAffectedItems() {
        ReversalHold h = hold("USER", INFORMER);
        assertThat(h.getState()).isEqualTo(ReversalHoldState.PENDING);
        assertThat(h.ids(ItemKind.TITLE)).containsExactly(TITLE);
        assertThat(h.ids(ItemKind.ALLOCATION)).hasSize(1);
    }

    @Test
    void neitherTheReverserNorTheInformerReleasesEvenAsAdmin() {
        ReversalHold h = hold("USER", INFORMER);
        assertThatThrownBy(() -> h.release(REVERSER, ReversalHoldReleaseReason.OTHER, "conferido", true, NOW))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("outra pessoa");
        assertThatThrownBy(() -> h.release(INFORMER, ReversalHoldReleaseReason.OTHER, "conferido", true, NOW))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("reversal_hold.self_release");
        assertThat(h.getState()).isEqualTo(ReversalHoldState.PENDING);
    }

    @Test
    void systemReversalCanBeReleasedByAnyReviewer() {
        ReversalHold h = hold("SYSTEM", null);
        h.release(REVIEWER, ReversalHoldReleaseReason.CREDITOR_CONFIRMED_NOT_RECEIVED, "  Credor confirmou  ", true, NOW);
        assertThat(h.getState()).isEqualTo(ReversalHoldState.RELEASED);
        assertThat(h.getReleaseNote()).isEqualTo("Credor confirmou");
        assertThat(h.getReleasedBy()).isEqualTo(REVIEWER);
    }

    @Test
    void releaseRequiresReasonNoteAndConfirmation() {
        ReversalHold h = hold("USER", null);
        assertThatThrownBy(() -> h.release(REVIEWER, null, "ok", true, NOW)).isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("reversal_hold.release_reason_required");
        assertThatThrownBy(() -> h.release(REVIEWER, ReversalHoldReleaseReason.OTHER, "  ", true, NOW))
                .isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("reversal_hold.release_note_required");
        assertThatThrownBy(() -> h.release(REVIEWER, ReversalHoldReleaseReason.OTHER, "x".repeat(501), true, NOW))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> h.release(REVIEWER, ReversalHoldReleaseReason.OTHER, "ok", false, NOW))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("reversal_hold.confirmation_required");
        assertThat(h.getState()).isEqualTo(ReversalHoldState.PENDING);
    }

    @Test
    void releasedHoldCannotBeReleasedAgain() {
        ReversalHold h = hold("USER", null);
        h.release(REVIEWER, ReversalHoldReleaseReason.OTHER, "ok", true, NOW);
        assertThatThrownBy(() -> h.release(UUID.randomUUID(), ReversalHoldReleaseReason.OTHER, "ok", true, NOW))
                .isInstanceOf(ConflictException.class)
                .extracting("code").isEqualTo("reversal_hold.not_pending");
    }

    @Test
    void anonymizationEndsThePendingHoldAndClearsTheReleaseNote() {
        ReversalHold pending = hold("USER", null);
        assertThat(pending.endByAnonymization(NOW)).isTrue();
        assertThat(pending.getState()).isEqualTo(ReversalHoldState.ENDED);
        assertThat(pending.getEndedReason()).isEqualTo("DEBTOR_ANONYMIZED");

        ReversalHold released = hold("USER", null);
        released.release(REVIEWER, ReversalHoldReleaseReason.OTHER, "citou o titular", true, NOW);
        assertThat(released.endByAnonymization(NOW)).isTrue();
        assertThat(released.getState()).isEqualTo(ReversalHoldState.RELEASED);
        assertThat(released.getReleaseNote()).isNull();
        assertThat(released.endByAnonymization(NOW)).isFalse();
    }

    @Test
    void theHoldIsIndependentOfTheTitleStatus() {
        // RS-109: nada no título (suspender, reativar, carga, quitar de novo) encerra a retenção — ela não conhece o status
        ReversalHold h = hold("USER", null);
        assertThat(java.util.Arrays.stream(ReversalHold.class.getDeclaredFields()).map(f -> f.getType().getSimpleName()))
                .doesNotContain("TitleStatus");
        assertThat(h.getState()).isEqualTo(ReversalHoldState.PENDING);
    }
}
