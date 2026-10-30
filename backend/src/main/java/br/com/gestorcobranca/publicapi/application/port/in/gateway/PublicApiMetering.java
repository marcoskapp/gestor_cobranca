package br.com.gestorcobranca.publicapi.application.port.in.gateway;

import java.time.Instant;
import java.util.UUID;

/**
 * Medição de cada requisição autenticada da API pública. <strong>Uso do adaptador HTTP do próprio módulo.</strong>
 * Grava o log de acesso da requisição ({@code PUBLIC_API_REQUEST}), publica as métricas técnicas e acumula o consumo
 * em memória para a gravação em lote (sem escrita de consumo no caminho da requisição).
 */
public interface PublicApiMetering {

    void record(RequestMeasurement measurement);

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
