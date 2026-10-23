package br.com.gestorcobranca.communication.infrastructure.persistence;

import br.com.gestorcobranca.communication.domain.model.ChannelType;
import br.com.gestorcobranca.communication.domain.model.Message;
import br.com.gestorcobranca.communication.domain.repository.MessageRepository;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface MessageJpaRepository extends JpaRepository<Message, UUID>, MessageRepository {

    @Override
    Optional<Message> findByIdempotencyKey(String idempotencyKey);

    @Override
    Optional<Message> findByChannelAndProviderMessageId(ChannelType channel, String providerMessageId);

    @Override
    Page<Message> findByConversationIdOrderByCreatedAtDesc(UUID conversationId, Pageable pageable);

    @Override
    default List<Message> findContentOlderThan(Instant cutoff, int limit) {
        return findContentOlderThan(cutoff, Limit.of(limit));
    }

    @Query("""
            select m from Message m where m.contentPurgedAt is null and m.createdAt < :cutoff
               and m.status <> br.com.gestorcobranca.communication.domain.model.MessageStatus.QUEUED
             order by m.createdAt
            """)
    List<Message> findContentOlderThan(Instant cutoff, Limit limit);
}
