package br.com.gestorcobranca.shared.logging;

/**
 * Chaves de MDC presentes em todas as linhas de log técnico. São emitidas como campos
 * estruturados (JSON) e indexáveis no CloudWatch Logs Insights.
 */
public final class MdcKeys {

    /** Id de correlação da requisição (cabeçalho {@code X-Request-Id}, gerado se ausente). */
    public static final String REQUEST_ID = "requestId";
    public static final String TENANT_ID = "tenantId";
    public static final String ACTOR_ID = "actorId";
    public static final String SESSION_ID = "sessionId";
    /** Nome do job/consumidor quando a execução não é uma requisição HTTP. */
    public static final String PROCESS = "process";
    /**
     * IP de origem já validado (IP literal): o da conexão ou, atrás do ALB, o primeiro salto não
     * confiável do {@code X-Forwarded-For} lido da direita ({@code shared.web.TrustedProxyFilter}).
     */
    public static final String CLIENT_IP = "clientIp";
    /** {@code "true"} quando o proxy informou um IP de cliente inválido (o evento é gravado sem IP). */
    public static final String CLIENT_IP_INVALID = "clientIpInvalid";
    public static final String USER_AGENT = "userAgent";
    /** Padrão da rota (ex.: {@code /api/v1/cases/{id}}), sem ids, para agregação de métricas. */
    public static final String ROUTE = "route";

    private MdcKeys() {
    }
}
