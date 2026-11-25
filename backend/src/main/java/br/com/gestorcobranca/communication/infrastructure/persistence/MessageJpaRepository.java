package br.com.gestorcobranca.communication.infrastructure.persistence;

import br.com.gestorcobranca.communication.domain.model.ChannelType;
import br.com.gestorcobranca.communication.domain.model.Conversation;
import br.com.gestorcobranca.communication.domain.model.Message;
import br.com.gestorcobranca.communication.domain.repository.MessageRepository;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface MessageJpaRepository extends JpaRepository<Message, UUID>, JpaSpecificationExecutor<Message>,
        MessageRepository {

    @Override
    default Page<Message> findByDebtor(UUID debtorId, UUID creditorId, Collection<UUID> allowedCreditorIds,
                                       Pageable pageable) {
        Specification<Message> spec = (root, query, cb) -> {
            Subquery<UUID> conversations = query.subquery(UUID.class);
            Root<Conversation> c = conversations.from(Conversation.class);
            List<Predicate> where = new ArrayList<>();
            where.add(cb.equal(c.get("debtorId"), debtorId));
            if (creditorId != null) {
                where.add(cb.equal(c.get("creditorId"), creditorId));
            }
            if (allowedCreditorIds != null) {
                where.add(allowedCreditorIds.isEmpty() ? cb.disjunction() : c.get("creditorId").in(allowedCreditorIds));
            }
            conversations.select(c.get("id")).where(where.toArray(Predicate[]::new));
            return root.get("conversationId").in(conversations);
        };
        return findAll(spec, PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"))));
    }

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

    @Override
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @Query(nativeQuery = true, value = """
            update communication.message
               set content_encrypted = null, subject_encrypted = null, variables_encrypted = null,
                   content_purged_at = coalesce(content_purged_at, :now),
                   provider_message_id = case
                       when provider_message_id is null or provider_message_id like 'anon:%' then provider_message_id
                       else 'anon:' || encode(sha256(convert_to(provider_message_id, 'UTF8')), 'hex') end,
                   version = version + 1, updated_at = :now
             where conversation_id = :conversationId
               and (content_purged_at is null
                    or (provider_message_id is not null and provider_message_id not like 'anon:%'))
            """)
    int purgeContentOfConversation(@Param("conversationId") UUID conversationId, @Param("now") Instant now);

    @Override
    List<Message> findByConversationIdAndStatus(UUID conversationId,
                                                br.com.gestorcobranca.communication.domain.model.MessageStatus status);
}
