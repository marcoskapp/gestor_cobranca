package br.com.gestorcobranca.shared.web;

/** Prefixos das APIs REST. Versão no caminho; mudanças incompatíveis exigem nova versão. */
public final class ApiPaths {

    /** API autenticada por usuários da assessoria (JWT). */
    public static final String V1 = "/api/v1";

    /** Webhooks de provedores externos (validação por assinatura). */
    public static final String WEBHOOKS = "/api/v1/webhooks";

    /** API do portal de autonegociação (autenticação do devedor). */
    public static final String PORTAL = "/api/portal/v1";

    /**
     * Raiz da API pública de integração dos credores (token de integração, cadeia de segurança própria —
     * módulo publicapi). Fora de {@code /api}: nunca aceita o JWT de usuário nem o token do portal.
     */
    public static final String PUBLIC_API = "/public-api";

    /** Versão 1 da API pública (versão na URL; política em docs/tecnico/desenho/api-publica.md). */
    public static final String PUBLIC_API_V1 = PUBLIC_API + "/v1";

    private ApiPaths() {
    }
}
