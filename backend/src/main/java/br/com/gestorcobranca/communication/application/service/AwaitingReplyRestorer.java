package br.com.gestorcobranca.communication.application.service;

import br.com.gestorcobranca.communication.domain.model.Message;
import br.com.gestorcobranca.communication.domain.model.MessageStatus;
import br.com.gestorcobranca.communication.domain.repository.ConversationRepository;
import br.com.gestorcobranca.communication.domain.repository.MessageRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Resposta a quem procurou ({@code RESPONSE}) que não chegou ao devedor — negada no envio, conta inativa, janela do
 * WhatsApp fechada, recusada pelo provedor (na hora ou depois, pelo webhook) ou com as tentativas esgotadas: a conversa
 * volta a aguardar retorno, e a próxima resposta continua fora dos limites de frequência da cobrança (decisão 1090).
 * Roda na reação a {@code MessageStatusChanged}, depois do commit da falha e em transação própria, com a conversa
 * relida sob lock.
 */
@Component
class AwaitingReplyRestorer {

    private final MessageRepository messages;
    private final ConversationRepository conversations;

    AwaitingReplyRestorer(MessageRepository messages, ConversationRepository conversations) {
        this.messages = messages;
        this.conversations = conversations;
    }

    void undelivered(UUID messageId) {
        Message message = messages.findById(messageId).orElse(null);
        // só a resposta que chegou ao job: a negada na verificação prévia (sem tentativa) não encerrou a espera
        if (message == null || message.getStatus() != MessageStatus.FAILED || message.getSendAttempts() == 0
                || !Message.CATEGORY_RESPONSE.equals(message.getContactCategory())) {
            return;
        }
        conversations.findByIdForUpdate(message.getConversationId()).ifPresent(c -> {
            c.resumeAwaiting(message.getQueuedAt());
            conversations.save(c);
        });
    }
}
