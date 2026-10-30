package br.com.gestorcobranca.publicapi.application.service;

import br.com.gestorcobranca.publicapi.application.port.in.IntegrationTokenUseCases.TokenView;
import br.com.gestorcobranca.publicapi.domain.model.IntegrationToken;
import br.com.gestorcobranca.publicapi.domain.model.TokenScope;

import java.time.Instant;

final class TokenViews {

    private TokenViews() {
    }

    static TokenView view(IntegrationToken t, Instant now) {
        return new TokenView(t.getId(), t.getCreditorId(), t.getName(), t.getDescription(), t.getDisplayPrefix(),
                TokenScope.codesOf(t.getScopes()), t.getRateLimitPerMinute(), t.getAllowedIps(), t.getExpiresAt(),
                t.stateAt(now).name(), t.getCreatedBy(), t.getCreatedAt(), t.getLastUsedAt(), t.getLastUsedIp(),
                t.getRevokedAt(), t.getRevokedBy(), t.getRevokeReason(), t.getRotatedAt(), t.getRotationCount(),
                t.getPreviousValidUntil() != null && t.getPreviousValidUntil().isAfter(now) ? t.getPreviousValidUntil() : null);
    }
}
