package br.com.gestorcobranca.publicapi.domain;

import br.com.gestorcobranca.publicapi.domain.model.IntegrationToken;
import br.com.gestorcobranca.publicapi.domain.model.TokenScope;
import br.com.gestorcobranca.publicapi.domain.model.TokenSecret;
import br.com.gestorcobranca.publicapi.domain.model.TokenState;
import br.com.gestorcobranca.publicapi.domain.model.TokenStatus;
import br.com.gestorcobranca.publicapi.domain.service.TokenHasher;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import org.junit.jupiter.api.Test;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pausa imposta pela plataforma: reversível, com motivo, retomada automática e precedência de revogado/expirado. */
class IntegrationTokenPauseTest {

    private final SecureRandom random = new SecureRandom();
    private final TokenHasher hasher = new TokenHasher(Map.of("t1", "chave-de-teste-com-32-bytes-ok!!".getBytes()), "t1");
    private final Instant now = Instant.parse("2026-11-04T12:00:00Z");
    private final UUID platformUser = UUID.randomUUID();

    private IntegrationToken token(Instant expiresAt) {
        TokenSecret secret = TokenSecret.generate(random);
        return new IntegrationToken(UUID.randomUUID(), "ERP", null, secret.prefix(), hasher.hash(secret), "t1",
                Set.of(TokenScope.TITLES_READ), 60, List.of(), expiresAt, now);
    }

    @Test
    void pauseIsReversibleAndRequiresReason() {
        IntegrationToken t = token(null);
        assertThatThrownBy(() -> t.pauseByPlatform(platformUser, " ", null, now))
                .isInstanceOf(BusinessRuleException.class);
        t.pauseByPlatform(platformUser, "  tráfego anômalo  ", null, now);
        assertThat(t.getStatus()).isEqualTo(TokenStatus.PAUSED);
        assertThat(t.stateAt(now.plus(Duration.ofDays(400)))).isEqualTo(TokenState.PAUSED);
        assertThat(t.getPauseReason()).isEqualTo("tráfego anômalo");
        assertThat(t.getPausedBy()).isEqualTo(platformUser);
        // já pausado: não pausa de novo
        assertThatThrownBy(() -> t.pauseByPlatform(platformUser, "de novo", null, now.plusSeconds(1)))
                .isInstanceOf(ConflictException.class);

        t.resume();
        assertThat(t.stateAt(now)).isEqualTo(TokenState.ACTIVE);
        assertThat(t.getPausedAt()).isNull();
        assertThat(t.getPauseReason()).isNull();
        assertThatThrownBy(t::resume).isInstanceOf(ConflictException.class);
    }

    @Test
    void scheduledResumeTakesEffectAtTheInstantEvenBeforeItIsRecorded() {
        IntegrationToken t = token(null);
        Instant resumeAt = now.plus(Duration.ofHours(2));
        t.pauseByPlatform(platformUser, "janela de manutenção do credor", resumeAt, now);
        assertThat(t.stateAt(resumeAt.minusMillis(1))).isEqualTo(TokenState.PAUSED);
        assertThat(t.resumeDue(resumeAt.minusMillis(1))).isFalse();
        assertThat(t.stateAt(resumeAt)).isEqualTo(TokenState.ACTIVE);
        assertThat(t.resumeDue(resumeAt)).isTrue();
        // ainda gravado como pausado até o job registrar
        assertThat(t.getStatus()).isEqualTo(TokenStatus.PAUSED);

        IntegrationToken other = token(null);
        assertThatThrownBy(() -> other.pauseByPlatform(platformUser, "x", now.minusSeconds(1), now))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> other.pauseByPlatform(platformUser, "x", now.plus(Duration.ofDays(91)), now))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void revokedAndExpiredPrevailAndRevocationEndsThePause() {
        IntegrationToken expiring = token(now.plus(Duration.ofHours(1)));
        expiring.pauseByPlatform(platformUser, "suspeita", null, now);
        assertThat(expiring.stateAt(now.plus(Duration.ofHours(2)))).isEqualTo(TokenState.EXPIRED);

        IntegrationToken t = token(null);
        t.pauseByPlatform(platformUser, "suspeita", now.plus(Duration.ofDays(1)), now);
        // a assessoria continua podendo rotacionar e revogar o token pausado
        TokenSecret next = TokenSecret.generate(random);
        t.rotate(hasher.hash(next), "t1", Duration.ZERO, now.plusSeconds(10));
        assertThat(t.stateAt(now.plusSeconds(10))).isEqualTo(TokenState.PAUSED);
        t.revoke(UUID.randomUUID(), "trocado", now.plusSeconds(20));
        assertThat(t.stateAt(now.plusSeconds(20))).isEqualTo(TokenState.REVOKED);
        assertThat(t.getPausedAt()).isNull();
        assertThat(t.getResumeAt()).isNull();
        assertThat(t.isRevokedByPlatform()).isFalse();
        assertThatThrownBy(() -> t.pauseByPlatform(platformUser, "x", null, now.plusSeconds(30)))
                .isInstanceOf(ConflictException.class);

        IntegrationToken byPlatform = token(null);
        byPlatform.revokeByPlatform(platformUser, "uso indevido", now);
        assertThat(byPlatform.isRevokedByPlatform()).isTrue();
        assertThat(byPlatform.getRevokedBy()).isEqualTo(platformUser);
    }
}
