package br.com.gestorcobranca.identity.infrastructure.persistence.creditor;

import br.com.gestorcobranca.identity.domain.model.creditor.CreditorUser;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface CreditorUserJpaRepository extends JpaRepository<CreditorUser, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from CreditorUser u where u.id = :id")
    Optional<CreditorUser> findByIdForUpdate(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from CreditorUser u where u.email = :email")
    Optional<CreditorUser> findByEmailForUpdate(String email);

    Optional<CreditorUser> findByEmail(String email);

    boolean existsByEmail(String email);

    List<CreditorUser> findByCreditorIdOrderByNameAsc(UUID creditorId);
}
