package br.com.gestorcobranca.publicapi.application.service;

import br.com.gestorcobranca.publicapi.application.port.in.IntegrationTokenUseCases.TokenView;
import br.com.gestorcobranca.publicapi.domain.model.IntegrationToken;
import br.com.gestorcobranca.publicapi.domain.model.TokenScope;
import br.com.gestorcobranca.publicapi.domain.model.TokenState;

import java.time.Instant;

final class TokenViews {

    private TokenViews() {
    }

    /**
     * Visão da assessoria: a pausa aparece com motivo, início e retomada agendada (decisão 766), sem identificar o
     * usuário da plataforma; uma pausa com retomada já vencida aparece como ativa.
     */
    static TokenView view(IntegrationToken t, Instant now) {
        boolean paused = t.stateAt(now) == TokenState.PAUSED;
        return new TokenView(t.getId(), t.getCreditorId(), t.getName(), t.getDescription(), t.getDisplayPrefix(),
                TokenScope.codesOf(t.getScopes()), t.getRateLimitPerMinute(), t.getAllowedIps(), t.getExpiresAt(),
                t.stateAt(now).name(), t.getCreatedBy(), t.getCreatedAt(), t.getLastUsedAt(), t.getLastUsedIp(),
                t.getRevokedAt(), t.isRevokedByPlatform() ? null : t.getRevokedBy(), t.getRevokeReason(),
                t.getRotatedAt(), t.getRotationCount(),
                t.getPreviousValidUntil() != null && t.getPreviousValidUntil().isAfter(now) ? t.getPreviousValidUntil() : null,
                t.isRevokedByPlatform(), paused ? t.getPausedAt() : null, paused ? t.getPauseReason() : null,
                paused ? t.getResumeAt() : null);
    }
}
