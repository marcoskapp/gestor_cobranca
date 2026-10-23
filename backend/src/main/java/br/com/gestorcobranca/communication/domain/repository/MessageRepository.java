package br.com.gestorcobranca.communication.domain.repository;

import br.com.gestorcobranca.communication.domain.model.ChannelType;
import br.com.gestorcobranca.communication.domain.model.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MessageRepository {

    Message save(Message message);

    Message saveAndFlush(Message message);

    Optional<Message> findById(UUID id);

    Optional<Message> findByIdempotencyKey(String idempotencyKey);

    Optional<Message> findByChannelAndProviderMessageId(ChannelType channel, String providerMessageId);

    Page<Message> findByConversationIdOrderByCreatedAtDesc(UUID conversationId, Pageable pageable);

    /**
     * Mensagens das conversas do devedor, das mais recentes para as mais antigas.
     *
     * @param allowedCreditorIds credores do escopo (nulo = todos)
     * @param creditorId         somente conversas deste credor (nulo = todas)
     */
    Page<Message> findByDebtor(UUID debtorId, UUID creditorId, Collection<UUID> allowedCreditorIds, Pageable pageable);

    /** Mensagens com conteúdo criadas antes do corte (retenção), mais antigas primeiro. */
    List<Message> findContentOlderThan(Instant cutoff, int limit);
}
