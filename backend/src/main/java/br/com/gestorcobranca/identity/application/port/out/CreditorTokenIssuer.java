package br.com.gestorcobranca.identity.application.port.out;

import br.com.gestorcobranca.identity.application.port.out.TokenIssuer.IssuedToken;
import br.com.gestorcobranca.identity.application.port.out.TokenIssuer.StepClaims;

import java.util.Optional;
import java.util.UUID;

/**
 * Tokens do portal do credor (ADR 0011): audiência {@code gestor-cobranca-creditor}, sem permissões nos claims (o
 * servidor resolve o perfil a cada requisição). As etapas do login ({@code creditor_mfa}, {@code creditor_pwd}) só são
 * lidas por este emissor — nunca servem ao login interno, e vice-versa.
 */
public interface CreditorTokenIssuer {

    IssuedToken issueAccessToken(UUID userId, UUID tenantId, UUID sessionId, UUID creditorId);

    String issueStepToken(String type, UUID userId, UUID tenantId);

    /** Valida assinatura, validade, audiência do credor e o tipo exato; vazio se inválido. */
    Optional<StepClaims> readStepToken(String token, String expectedType);
}
