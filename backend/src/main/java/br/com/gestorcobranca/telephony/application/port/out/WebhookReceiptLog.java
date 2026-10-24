package br.com.gestorcobranca.telephony.application.port.out;

import java.time.Instant;
import java.util.UUID;

/**
 * Recibos de entregas de webhook (corpo idêntico reenviado = replay) e eventos do provedor já
 * aplicados (idempotência por id do evento), no tenant corrente.
 */
public interface WebhookReceiptLog {

    /** @return true se o corpo ainda não tinha sido recebido para a conta (registra o recibo) */
    boolean registerBodyIfFirst(UUID accountId, String payloadSha256, Instant receivedAt);

    /** @return true se o evento ainda não tinha sido aplicado (registra o evento) */
    boolean registerEventIfFirst(UUID accountId, String providerEventId, String type, Instant receivedAt);

    /** Remove recibos e registros de eventos anteriores ao corte. */
    int purgeBefore(Instant cutoff);
}
