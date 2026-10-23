package br.com.gestorcobranca.identity.infrastructure.persistence;

import br.com.gestorcobranca.identity.domain.model.AuthSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface AuthSessionJpaRepository extends JpaRepository<AuthSession, UUID> {

    List<AuthSession> findByUserIdAndStatus(UUID userId, AuthSession.Status status);

    @Query("""
            select s from AuthSession s
            where s.userId in :userIds and s.startedAt < :to and s.lastSeenAt > :from
            """)
    List<AuthSession> findOverlapping(java.util.Collection<UUID> userIds, Instant from, Instant to);

    @Modifying
    @Query("""
            update AuthSession s set s.lastSeenAt = :now
            where s.id = :sessionId and s.status = br.com.gestorcobranca.identity.domain.model.AuthSession.Status.ACTIVE
              and s.lastSeenAt < :now
            """)
    int touch(UUID sessionId, Instant now);

    @Modifying
    @Query("""
            update AuthSession s set s.status = br.com.gestorcobranca.identity.domain.model.AuthSession.Status.EXPIRED,
                   s.endedAt = :now, s.endReason = 'expired', s.version = s.version + 1
            where s.status = br.com.gestorcobranca.identity.domain.model.AuthSession.Status.ACTIVE
              and (s.lastSeenAt < :idleBefore or s.absoluteExpiresAt < :now)
            """)
    int expireStale(Instant idleBefore, Instant now);
}
