package br.com.gestorcobranca.publicapi.application.port.in.gateway;

import br.com.gestorcobranca.shared.security.Actor;

import java.util.Set;
import java.util.UUID;

/**
 * Autenticação das requisições da API pública pelo token de integração. <strong>Uso do adaptador HTTP do próprio
 * módulo</strong> (cadeia de filtros de {@code /public-api/**}); exposta como porta para manter a separação entre o
 * adaptador e a regra.
 * <p>
 * Cada verificação é feita no banco a cada requisição (sem cache): revogação, expiração e troca de IPs valem
 * imediatamente. Recusas são registradas no log de acesso ({@code INTEGRATION_TOKEN_REJECTED}) com o motivo real; ao
 * cliente vai sempre a mesma resposta genérica (exceto IP fora da lista, que exige o segredo correto).
 */
public interface IntegrationAuthentication {

    Result authenticate(String presentedToken, String clientIp);

    /** Limite de requisições por minuto do token (janela fixa por instância). */
    RateDecision acquire(UUID tokenId, int limitPerMinute);

    sealed interface Result permits Authenticated, Rejected {
    }

    /**
     * @param actor  ator {@code INTEGRATION}: id = token, escopo = somente o credor do token, permissões mínimas dos escopos
     * @param scopes códigos dos escopos concedidos
     */
    record Authenticated(Actor actor, Set<String> scopes, UUID tokenId, String prefix, UUID creditorId,
                         int rateLimitPerMinute) implements Result {
    }

    /**
     * @param reason   motivo interno (log de acesso): missing, malformed, unknown, secret_mismatch, revoked, expired,
     *                 ip_not_allowed, tenant_inactive, creditor_inactive
     * @param status   401 ou 403
     * @param tenantId assessoria do token, quando identificada
     * @param prefix   prefixo exibível, quando identificado
     */
    record Rejected(String reason, int status, UUID tenantId, String prefix) implements Result {
    }

    /** @param resetSeconds segundos até a próxima janela */
    record RateDecision(boolean allowed, int limit, int remaining, long resetSeconds) {
    }
}
