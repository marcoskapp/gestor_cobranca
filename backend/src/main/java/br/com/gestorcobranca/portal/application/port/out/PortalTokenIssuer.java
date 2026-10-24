package br.com.gestorcobranca.portal.application.port.out;

import java.time.Instant;
import java.util.UUID;

/**
 * Emissão do token do portal: JWT RS256 da plataforma com {@code typ=portal} e audiência própria
 * ({@code gestor-cobranca-portal}), nunca aceito na API interna.
 */
public interface PortalTokenIssuer {

    IssuedToken issue(UUID tenantId, UUID sessionId, UUID debtorId, UUID creditorId);

    record IssuedToken(String value, Instant expiresAt) {
    }
}
