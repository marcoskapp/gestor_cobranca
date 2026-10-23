package br.com.gestorcobranca.communication.application.port.out;

import java.time.Instant;
import java.util.UUID;

/** Recibos de entregas de webhook já processadas (corpo idêntico reenviado = replay). */
public interface WebhookReceiptLog {

    /** @return true se o corpo ainda não tinha sido recebido para a conta (registra o recibo) */
    boolean registerIfFirst(UUID channelAccountId, String payloadSha256, Instant receivedAt);

    /** Remove recibos anteriores ao corte (tenant corrente). */
    int purgeBefore(Instant cutoff);
}
