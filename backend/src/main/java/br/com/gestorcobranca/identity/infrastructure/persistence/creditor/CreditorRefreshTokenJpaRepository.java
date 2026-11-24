package br.com.gestorcobranca.identity.infrastructure.persistence.creditor;

import br.com.gestorcobranca.identity.domain.model.creditor.CreditorRefreshToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface CreditorRefreshTokenJpaRepository extends JpaRepository<CreditorRefreshToken, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from CreditorRefreshToken t where t.tokenHash = :tokenHash")
    Optional<CreditorRefreshToken> findByTokenHashForUpdate(String tokenHash);

    List<CreditorRefreshToken> findBySessionIdAndStatus(UUID sessionId, CreditorRefreshToken.Status status);
}
