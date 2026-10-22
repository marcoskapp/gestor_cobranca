package br.com.gestorcobranca.shared.web;

/** Prefixos das APIs REST. Versão no caminho; mudanças incompatíveis exigem nova versão. */
public final class ApiPaths {

    /** API autenticada por usuários da assessoria (JWT). */
    public static final String V1 = "/api/v1";

    /** Webhooks de provedores externos (validação por assinatura). */
    public static final String WEBHOOKS = "/api/v1/webhooks";

    /** API do portal de autonegociação (autenticação do devedor). */
    public static final String PORTAL = "/api/portal/v1";

    private ApiPaths() {
    }
}
