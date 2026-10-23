package br.com.gestorcobranca.payment.infrastructure.persistence;

import br.com.gestorcobranca.payment.domain.model.StatementImport;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

interface StatementImportJpa extends JpaRepository<StatementImport, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from StatementImport s where s.id = :id")
    Optional<StatementImport> lockById(@Param("id") UUID id);

    boolean existsByFileSha256(String sha256);
}
