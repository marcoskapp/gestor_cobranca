package br.com.gestorcobranca.helpdesk.infrastructure.persistence;

import br.com.gestorcobranca.helpdesk.domain.model.SupportTicket;
import br.com.gestorcobranca.helpdesk.domain.repository.SupportTicketRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SupportTicketJpaRepository extends JpaRepository<SupportTicket, UUID>, SupportTicketRepository {

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from SupportTicket t where t.id = :id")
    Optional<SupportTicket> findByIdForUpdate(@Param("id") UUID id);

    @Query("""
            select t from SupportTicket t
            where t.userId = :userId and t.contentSha256 = :sha and t.id <> :excludeId and t.openedAt >= :since
              and t.status = br.com.gestorcobranca.helpdesk.domain.model.TicketStatus.OPENED
            order by t.openedAt desc""")
    List<SupportTicket> findOpened(@Param("userId") UUID userId, @Param("sha") String sha,
                                   @Param("since") Instant since, @Param("excludeId") UUID excludeId, Limit limit);

    @Override
    default Optional<SupportTicket> findOpenedDuplicate(UUID userId, String contentSha256, Instant since, UUID excludeId) {
        return findOpened(userId, contentSha256, since, excludeId, Limit.of(1)).stream().findFirst();
    }

    @Query("""
            select t from SupportTicket t
            where t.expiresAt <= :now and t.status in (br.com.gestorcobranca.helpdesk.domain.model.TicketStatus.DRAFT,
                                                       br.com.gestorcobranca.helpdesk.domain.model.TicketStatus.FAILED)
            order by t.expiresAt""")
    List<SupportTicket> findPendingExpired(@Param("now") Instant now, Limit limit);

    @Override
    default List<SupportTicket> findPendingExpiredBefore(Instant now, int limit) {
        return findPendingExpired(now, Limit.of(limit));
    }
}
