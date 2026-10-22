package br.com.gestorcobranca.identity.infrastructure.persistence;

import br.com.gestorcobranca.identity.domain.model.ActionToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface ActionTokenJpaRepository extends JpaRepository<ActionToken, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from ActionToken t where t.tokenHash = :tokenHash")
    Optional<ActionToken> findByTokenHashForUpdate(String tokenHash);

    @Query("""
            select t from ActionToken t
            where t.userId = :userId and t.purpose = :purpose and t.usedAt is null and t.invalidated = false
              and t.expiresAt > :now
            """)
    List<ActionToken> findUsable(UUID userId, ActionToken.Purpose purpose, Instant now);
}
