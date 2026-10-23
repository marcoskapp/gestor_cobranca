package br.com.gestorcobranca.communication.application.service;

import br.com.gestorcobranca.communication.domain.model.Conversation;
import br.com.gestorcobranca.communication.domain.model.Message;
import br.com.gestorcobranca.communication.domain.model.MessageStatus;
import br.com.gestorcobranca.communication.domain.repository.ConversationRepository;
import br.com.gestorcobranca.communication.domain.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Marca a mensagem como {@code FAILED/RETRIES_EXHAUSTED} em transação própria, na última tentativa
 * do job de envio: a transação do job é desfeita (o job vai para DEAD), mas a falha da mensagem
 * fica registrada e comunicada. A transação do job não altera a linha da mensagem antes do envio,
 * de modo que não há disputa de lock com esta transação.
 */
@Component
class MessageFailureRecorder {

    private static final Logger LOG = LoggerFactory.getLogger(MessageFailureRecorder.class);
    static final String RETRIES_EXHAUSTED = "RETRIES_EXHAUSTED";

    private final MessageRepository messages;
    private final ConversationRepository conversations;
    private final MessageRecorder recorder;
    private final Clock clock;

    MessageFailureRecorder(MessageRepository messages, ConversationRepository conversations, MessageRecorder recorder,
                           Clock clock) {
        this.messages = messages;
        this.conversations = conversations;
        this.recorder = recorder;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void exhausted(UUID messageId, int attempts, String detail) {
        Message message = messages.findById(messageId).orElse(null);
        if (message == null || message.getStatus() != MessageStatus.QUEUED) {
            return;
        }
        Conversation conversation = conversations.findById(message.getConversationId()).orElseThrow();
        message.recordAttempt(attempts);
        message.markFailed(RETRIES_EXHAUSTED, detail == null ? "Falha transitória do provedor" : detail, Instant.now(clock));
        messages.save(message);
        recorder.failed(message, conversation);
        LOG.atError().addKeyValue("messageId", messageId).addKeyValue("attempts", attempts)
                .log("Mensagem não enviada: tentativas esgotadas");
    }
}
