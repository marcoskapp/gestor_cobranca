package br.com.gestorcobranca.publicapi.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Emissão de token de integração da API pública para um credor do escopo do ator ({@code PUBLIC_API_TOKEN_MANAGE}).
 * O segredo completo só existe no retorno desta chamada: o sistema guarda apenas o hash. Audita
 * ({@code integration_token.created}), registra {@code INTEGRATION_TOKEN_CREATED} no log de acesso e o evento de
 * negócio {@code public_api.token_created}.
 */
public interface IssueIntegrationToken {

    IssuedToken issue(IssueTokenCommand command);

    /**
     * @param scopes             códigos dos escopos ({@code titles:read}, {@code imports:write}…)
     * @param rateLimitPerMinute limite de requisições por minuto (nulo = padrão da configuração)
     * @param allowedIps         faixas CIDR ou IPs permitidos (vazio = qualquer origem)
     * @param expiresAt          expiração opcional
     */
    record IssueTokenCommand(UUID creditorId, String name, String description, Set<String> scopes,
                             Integer rateLimitPerMinute, List<String> allowedIps, Instant expiresAt) {
    }

    /** @param token valor completo ({@code gck_<prefixo>.<segredo>}), exibido uma única vez */
    record IssuedToken(IntegrationTokenUseCases.TokenView details, String token) {

        @Override
        public String toString() {
            return "IssuedToken[" + details.prefix() + ".***]";
        }
    }
}
