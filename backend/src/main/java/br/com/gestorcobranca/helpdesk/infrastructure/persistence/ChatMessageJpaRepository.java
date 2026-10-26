package br.com.gestorcobranca.helpdesk.infrastructure.persistence;

import br.com.gestorcobranca.helpdesk.domain.model.ChatMessage;
import br.com.gestorcobranca.helpdesk.domain.repository.ChatMessageRepository;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface ChatMessageJpaRepository extends JpaRepository<ChatMessage, UUID>, ChatMessageRepository {

    @Query("""
            select count(m) from ChatMessage m
            where m.userId = :userId and m.occurredAt >= :since
              and m.role = br.com.gestorcobranca.helpdesk.domain.model.MessageRole.USER""")
    long countQuestionsSince(@Param("userId") UUID userId, @Param("since") Instant since);

    @Query("""
            select coalesce(sum(m.costAmount), 0) from ChatMessage m
            where m.userId = :userId and m.occurredAt >= :since""")
    BigDecimal costSince(@Param("userId") UUID userId, @Param("since") Instant since);

    @Override
    default UsageTotals usageSince(UUID userId, Instant since) {
        return new UsageTotals(countQuestionsSince(userId, since), costSince(userId, since));
    }

    @Query("""
            select m from ChatMessage m
            where m.contentPurgedAt is null and m.occurredAt < :before
            order by m.occurredAt""")
    List<ChatMessage> findContentBefore(@Param("before") Instant before, Limit limit);

    @Override
    default List<ChatMessage> findContentBefore(Instant before, int limit) {
        return findContentBefore(before, Limit.of(limit));
    }
}
