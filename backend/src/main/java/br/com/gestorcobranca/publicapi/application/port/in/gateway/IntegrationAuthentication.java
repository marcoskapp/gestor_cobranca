package br.com.gestorcobranca.publicapi.application.port.in.gateway;

import br.com.gestorcobranca.shared.security.Actor;

import java.util.Set;
import java.util.UUID;

/**
 * Autenticação das requisições da API pública pelo token de integração. <strong>Uso do adaptador HTTP do próprio
 * módulo</strong> (cadeia de filtros de {@code /public-api/**}); exposta como porta para manter a separação entre o
 * adaptador e a regra.
 * <p>
 * Cada verificação é feita no banco a cada requisição (sem cache): revogação, pausa, retomada, expiração e troca de
 * IPs valem imediatamente em todas as instâncias. Recusas são registradas no log de acesso
 * ({@code INTEGRATION_TOKEN_REJECTED}) com o motivo real; ao cliente vai sempre a mesma resposta genérica, exceto as
 * que exigem o segredo correto (IP fora da lista, credor inativo e token pausado pela plataforma).
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
     * @param reason     motivo interno (log de acesso): missing, malformed, unknown, secret_mismatch, revoked, expired,
     *                   paused, ip_not_allowed, tenant_inactive, creditor_inactive
     * @param status     401 ou 403
     * @param tenantId   assessoria do token, quando identificada
     * @param prefix     prefixo exibível, quando identificado
     * @param tokenId    token cujo segredo conferiu (a recusa é tráfego dele e é medida); nulo quando o portador não
     *                   foi provado (ausente, malformado, desconhecido ou segredo errado)
     * @param creditorId credor do token, com {@code tokenId}
     */
    record Rejected(String reason, int status, UUID tenantId, String prefix, UUID tokenId, UUID creditorId)
            implements Result {

        /** Recusa atribuível ao token (segredo conferido): entra na medição do tráfego recusado. */
        public boolean attributable() {
            return tokenId != null && creditorId != null && tenantId != null;
        }

        public boolean paused() {
            return "paused".equals(reason);
        }
    }

    /** @param resetSeconds segundos até a próxima janela */
    record RateDecision(boolean allowed, int limit, int remaining, long resetSeconds) {
    }
}
