package br.com.gestorcobranca.communication.application.port.in;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Avisos em tempo real da caixa de entrada (F12). O aviso diz apenas <em>qual</em> conversa mudou
 * (nova mensagem, situação de entrega, dono, encerramento); quem recebe busca os dados pelos
 * endpoints normais, que reaplicam permissão e escopo. Avisos chegam de qualquer instância pelo
 * LISTEN/NOTIFY do PostgreSQL; sem garantia de entrega (o cliente mantém uma atualização periódica
 * de segurança e recarrega tudo no aviso {@code RESYNC}).
 */
public interface InboxRealtimeUseCases {

    /**
     * Assina os avisos da assessoria do ator corrente, filtrados pelo escopo de credores (conversas
     * sem credor só para quem tem todos os credores). Exige {@code INBOX_VIEW}.
     */
    Subscription subscribe(Consumer<InboxSignal> listener);

    /**
     * @param kind           CONVERSATION (mudou a conversa {@code conversationId}) ou RESYNC (avisos podem
     *                       ter se perdido: recarregar a lista e a conversa aberta)
     * @param conversationId nulo em RESYNC
     */
    record InboxSignal(String kind, UUID conversationId) {

        public static final String CONVERSATION = "CONVERSATION";
        public static final String RESYNC = "RESYNC";
    }

    /** Assinatura ativa; {@link #close()} é idempotente. */
    interface Subscription extends AutoCloseable {

        @Override
        void close();
    }
}
