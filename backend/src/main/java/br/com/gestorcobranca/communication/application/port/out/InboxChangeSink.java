package br.com.gestorcobranca.communication.application.port.out;

import java.util.UUID;

/**
 * Destino dos avisos de mudança de conversa recebidos pelo adaptador de escuta do banco
 * ({@code communication_inbox}, publicado por gatilho na confirmação da transação).
 */
public interface InboxChangeSink {

    /** @param creditorId credor da conversa (nulo = contato não identificado) */
    void changed(UUID tenantId, UUID conversationId, UUID creditorId);

    /** A escuta foi restabelecida após falha: avisos do intervalo podem ter se perdido. */
    void resync();
}
