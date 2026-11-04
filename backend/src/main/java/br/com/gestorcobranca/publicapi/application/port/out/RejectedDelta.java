package br.com.gestorcobranca.publicapi.application.port.out;

import java.time.Instant;
import java.util.UUID;

/**
 * Chamadas recusadas na autenticação de um token identificado, acumuladas em memória por (credor, token, hora, motivo,
 * método), prontas para gravar em lote. Só tráfego: nunca cobradas.
 */
public record RejectedDelta(UUID tenantId, UUID creditorId, UUID tokenId, Instant hourStart, String reason,
                            String method, long attempts) {
}
