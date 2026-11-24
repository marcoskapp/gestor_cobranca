package br.com.gestorcobranca.identity.infrastructure.persistence.creditor;

import br.com.gestorcobranca.identity.domain.model.creditor.CreditorSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface CreditorSessionJpaRepository extends JpaRepository<CreditorSession, UUID> {

    List<CreditorSession> findByUserIdAndStatus(UUID userId, CreditorSession.Status status);

    List<CreditorSession> findByCreditorIdAndStatus(UUID creditorId, CreditorSession.Status status);

    @Modifying
    @Query("""
            update CreditorSession s set s.lastSeenAt = :now
            where s.id = :sessionId
              and s.status = br.com.gestorcobranca.identity.domain.model.creditor.CreditorSession.Status.ACTIVE
              and s.lastSeenAt < :now
            """)
    int touch(UUID sessionId, Instant now);

    @Modifying
    @Query("""
            update CreditorSession s
               set s.status = br.com.gestorcobranca.identity.domain.model.creditor.CreditorSession.Status.EXPIRED,
                   s.endedAt = :now, s.endReason = 'expired', s.version = s.version + 1
            where s.status = br.com.gestorcobranca.identity.domain.model.creditor.CreditorSession.Status.ACTIVE
              and (s.lastSeenAt < :idleBefore or s.absoluteExpiresAt < :now)
            """)
    int expireStale(Instant idleBefore, Instant now);
}
