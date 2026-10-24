package br.com.gestorcobranca.portal.infrastructure.persistence;

import br.com.gestorcobranca.portal.domain.model.PortalSession;
import br.com.gestorcobranca.portal.domain.repository.PortalSessionRepository;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

interface PortalSessionJpaRepository extends JpaRepository<PortalSession, UUID>, PortalSessionRepository {

    @Override
    @Query("""
            select s from PortalSession s
             where s.debtorId = :debtorId
               and s.status = br.com.gestorcobranca.portal.domain.model.SessionStatus.ACTIVE
             order by s.createdAt, s.id
            """)
    List<PortalSession> findActiveByDebtorId(UUID debtorId);

    @Override
    List<PortalSession> findByDebtorId(UUID debtorId);

    @Override
    default List<PortalSession> findActiveStale(Instant absoluteBefore, Instant idleBefore, int limit) {
        return findStale(absoluteBefore, idleBefore, Limit.of(limit));
    }

    @Query("""
            select s from PortalSession s
             where s.status = br.com.gestorcobranca.portal.domain.model.SessionStatus.ACTIVE
               and (s.expiresAt <= :absoluteBefore or s.lastSeenAt <= :idleBefore)
             order by s.id
            """)
    List<PortalSession> findStale(Instant absoluteBefore, Instant idleBefore, Limit limit);

    @Override
    @Query("""
            select s from PortalSession s
             where s.configurationId = :configurationId and (:debtorId is null or s.debtorId = :debtorId)
            """)
    Page<PortalSession> search(UUID configurationId, UUID debtorId, Pageable pageable);

    @Override
    @Modifying
    @Query("""
            update PortalSession s set s.lastSeenAt = :at
             where s.id = :sessionId and s.status = br.com.gestorcobranca.portal.domain.model.SessionStatus.ACTIVE
               and s.lastSeenAt < :at
            """)
    int touch(UUID sessionId, Instant at);
}
