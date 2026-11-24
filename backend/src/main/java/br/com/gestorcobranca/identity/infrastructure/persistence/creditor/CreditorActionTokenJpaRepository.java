package br.com.gestorcobranca.identity.infrastructure.persistence.creditor;

import br.com.gestorcobranca.identity.domain.model.creditor.CreditorActionToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface CreditorActionTokenJpaRepository extends JpaRepository<CreditorActionToken, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from CreditorActionToken t where t.tokenHash = :tokenHash")
    Optional<CreditorActionToken> findByTokenHashForUpdate(String tokenHash);

    @Query("select t from CreditorActionToken t where t.userId = :userId and t.usedAt is null and t.invalidated = false")
    List<CreditorActionToken> findUsable(UUID userId);
}
