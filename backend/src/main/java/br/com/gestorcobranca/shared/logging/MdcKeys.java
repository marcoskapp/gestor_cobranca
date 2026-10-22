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
    /** IP de origem (considera {@code X-Forwarded-For} do ALB). */
    public static final String CLIENT_IP = "clientIp";
    public static final String USER_AGENT = "userAgent";
    /** Padrão da rota (ex.: {@code /api/v1/cases/{id}}), sem ids, para agregação de métricas. */
    public static final String ROUTE = "route";

    private MdcKeys() {
    }
}
