package br.com.gestorcobranca.payment.application.port.out;

import java.time.Instant;
import java.util.UUID;

/** Recibos de notificações Pix já recebidas (corpo idêntico reenviado = replay sem efeito). */
public interface WebhookReceiptLog {

    /** @return true se o corpo ainda não tinha sido recebido para a conta (registra o recibo) */
    boolean registerIfFirst(UUID pixAccountId, String payloadSha256, Instant receivedAt);

    /** Remove recibos anteriores ao corte (tenant corrente). */
    int purgeBefore(Instant cutoff);
}
