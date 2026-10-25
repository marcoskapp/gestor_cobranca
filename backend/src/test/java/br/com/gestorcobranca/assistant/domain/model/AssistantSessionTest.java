package br.com.gestorcobranca.assistant.domain.model;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssistantSessionTest {

    private static AssistantSession session() {
        return new AssistantSession(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, null, true, "{}");
    }

    @Test
    void identityFailsAfterMaxAttempts() {
        AssistantSession s = session();
        s.identityFailed(2);
        assertThat(s.getIdentityStatus()).isEqualTo(IdentityStatus.PENDING);
        s.identityFailed(2);
        assertThat(s.getIdentityStatus()).isEqualTo(IdentityStatus.FAILED);
        assertThat(s.identityConfirmed()).isFalse();
    }

    @Test
    void turnLifecycleAccumulatesUsage() {
        AssistantSession s = session();
        UUID turn = UUID.randomUUID();
        s.begin(turn, Instant.now(), Instant.now(), null);
        assertThatThrownBy(() -> s.begin(UUID.randomUUID(), null, Instant.now(), null))
                .hasMessageContaining("andamento");
        s.finish(turn, 100, 20, new BigDecimal("0.01"), "USD");
        assertThat(s.getTurns()).isEqualTo(1);
        assertThat(s.getCostAmount()).isEqualByComparingTo("0.01");
        assertThat(s.getPendingTurnId()).isNull();
    }

    @Test
    void reopenStartsNewEpisodeKeepingRecentIdentity() {
        AssistantSession s = session();
        Instant now = Instant.now();
        s.identityVerified(now);
        s.handedOff(HandoffReason.HUMAN_REQUESTED, now);
        s.reopen(now.plusSeconds(3600), true, "{}");
        assertThat(s.getEpisode()).isEqualTo(2);
        assertThat(s.getIdentityStatus()).isEqualTo(IdentityStatus.VERIFIED);
        s.handedOff(HandoffReason.HUMAN_REQUESTED, now);
        s.reopen(now.plus(AssistantSession.IDENTITY_VALIDITY).plusSeconds(1), true, "{}");
        assertThat(s.getIdentityStatus()).isEqualTo(IdentityStatus.PENDING);
    }

    @Test
    void reopeningDoesNotGiveNewIdentityAttempts() {
        AssistantSession s = session();
        Instant now = Instant.now();
        s.identityFailed(3);
        s.identityFailed(3);
        s.handedOff(HandoffReason.HUMAN_REQUESTED, now);
        s.reopen(now.plusSeconds(60), true, "{}");
        assertThat(s.getIdentityAttempts()).isEqualTo(2);
        assertThat(s.getIdentityStatus()).isEqualTo(IdentityStatus.PENDING);
        s.identityFailed(3);
        assertThat(s.getIdentityStatus()).isEqualTo(IdentityStatus.FAILED);

        // devolvida de novo ao assistente (ex.: ação de campanha): continua sem identidade confirmada
        s.handedOff(HandoffReason.IDENTITY_NOT_CONFIRMED, now);
        s.reopen(now.plusSeconds(120), true, "{}");
        assertThat(s.getIdentityStatus()).isEqualTo(IdentityStatus.FAILED);
        assertThat(s.getIdentityAttempts()).isEqualTo(3);
        assertThat(s.identityConfirmed()).isFalse();
    }

    @Test
    void publishedPolicyIsImmutable() {
        AssistantPolicy p = new AssistantPolicy(null, null, "geral", "Geral", PolicySettings.defaults(AssistantMode.SHADOW), "{}");
        p.publish(Instant.now(), UUID.randomUUID(), 1);
        assertThatThrownBy(() -> p.update("x", PolicySettings.defaults(AssistantMode.AUTONOMOUS), "{}"))
                .hasMessageContaining("imutável");
        assertThat(p.getPolicyVersion()).isEqualTo(1);
    }
}
