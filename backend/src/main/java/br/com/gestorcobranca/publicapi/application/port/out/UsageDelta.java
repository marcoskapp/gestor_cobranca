package br.com.gestorcobranca.publicapi.application.port.out;

import java.time.Instant;
import java.util.UUID;

/**
 * Consumo acumulado em memória de uma chave (credor, token, hora, método, rota), pronto para gravar em lote.
 * {@code billableBytes*}: tráfego das requisições cobráveis (2xx/3xx).
 */
public record UsageDelta(UUID tenantId, UUID creditorId, UUID tokenId, Instant hourStart, String method, String route,
                         long requests, long throttled, long clientErrors, long serverErrors, long bytesIn,
                         long bytesOut, long billableBytesIn, long billableBytesOut, long records,
                         long latencyMsTotal) {
}
