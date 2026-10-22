package br.com.gestorcobranca.identity.application.port.out;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Emissão e leitura de JWTs (implementado com Spring Security/Nimbus na infraestrutura). */
public interface TokenIssuer {

    IssuedToken issueAccessToken(AccessClaims claims);

    String issueStepToken(String type, UUID userId, UUID tenantId, Duration ttl);

    /** Valida assinatura, validade e tipo; vazio se inválido. */
    Optional<StepClaims> readStepToken(String token, String expectedType);

    record AccessClaims(UUID userId, UUID tenantId, UUID sessionId, String name, Set<String> permissions,
                        boolean allCreditors, Set<UUID> creditorIds) {
    }

    record IssuedToken(String value, Instant expiresAt) {
    }

    record StepClaims(UUID userId, UUID tenantId) {
    }
}
