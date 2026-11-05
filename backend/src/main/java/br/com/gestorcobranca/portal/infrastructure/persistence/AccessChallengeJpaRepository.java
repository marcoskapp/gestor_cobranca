package br.com.gestorcobranca.portal.infrastructure.persistence;

import br.com.gestorcobranca.portal.domain.model.AccessChallenge;
import br.com.gestorcobranca.portal.domain.repository.AccessChallengeRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

interface AccessChallengeJpaRepository extends JpaRepository<AccessChallenge, UUID>, AccessChallengeRepository {

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AccessChallenge c where c.id = :id")
    Optional<AccessChallenge> findByIdForUpdate(UUID id);

    @Override
    @Modifying
    @Query(value = """
            delete from portal.access_challenge
             where id in (select id from portal.access_challenge
                           where tenant_id = shared.current_tenant_id() and expires_at < :cutoff
                           limit :max)
            """, nativeQuery = true)
    int deleteExpiredBefore(Instant cutoff, @Param("max") int limit);
}
