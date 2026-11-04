package br.com.gestorcobranca.publicapi.application.port.in.gateway;

import java.time.Instant;
import java.util.UUID;

/**
 * Medição de cada requisição autenticada da API pública. <strong>Uso do adaptador HTTP do próprio módulo.</strong>
 * Grava o log de acesso da requisição ({@code PUBLIC_API_REQUEST}), publica as métricas técnicas e acumula o consumo
 * em memória para a gravação em lote (sem escrita de consumo no caminho da requisição). Também mede, à parte, as
 * chamadas recusadas na autenticação de um token identificado (tráfego recusado, nunca cobrado).
 */
public interface PublicApiMetering {

    void record(RequestMeasurement measurement);

    /**
     * Chamada recusada na autenticação de um token identificado (segredo correto): soma ao tráfego recusado do token
     * (nunca cobrado) e à métrica técnica. O log de acesso da recusa já foi gravado pela autenticação.
     */
    void recordRejected(RejectedAttempt attempt);

    /** @param reason motivo da recusa (paused, revoked, expired, tenant_inactive, creditor_inactive, ip_not_allowed) */
    record RejectedAttempt(UUID tenantId, UUID creditorId, UUID tokenId, String method, String reason, Instant at) {
    }

    /**
     * @param route   padrão da rota ({@code /public-api/v1/titles/{id}}) ou {@code UNMATCHED}
     * @param bytesIn corpo recebido (maior entre o lido e o {@code Content-Length})
     * @param bytesOut corpo enviado antes da compressão HTTP
     * @param records registros processados informados pelo endpoint
     */
    record RequestMeasurement(UUID tenantId, UUID creditorId, UUID tokenId, String tokenPrefix, String method,
                              String route, int status, long bytesIn, long bytesOut, long records, long latencyMs,
                              String clientIp, Instant at) {
    }
}
