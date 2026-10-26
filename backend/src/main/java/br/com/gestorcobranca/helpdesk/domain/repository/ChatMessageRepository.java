package br.com.gestorcobranca.helpdesk.domain.repository;

import br.com.gestorcobranca.helpdesk.domain.model.ChatMessage;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatMessageRepository {

    ChatMessage save(ChatMessage message);

    Optional<ChatMessage> findById(UUID id);

    List<ChatMessage> findByConversationIdOrderBySequenceAsc(UUID conversationId);

    /** Consumo do usuário a partir de um instante (limites diários). */
    UsageTotals usageSince(UUID userId, Instant since);

    /** Lote de mensagens com conteúdo anterior ao instante (retenção). */
    List<ChatMessage> findContentBefore(Instant before, int limit);

    /** @param costAmount soma do custo das respostas */
    record UsageTotals(long questions, java.math.BigDecimal costAmount) {
    }
}
