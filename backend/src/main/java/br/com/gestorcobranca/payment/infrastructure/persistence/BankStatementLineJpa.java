package br.com.gestorcobranca.payment.infrastructure.persistence;

import br.com.gestorcobranca.payment.domain.model.BankStatementLine;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface BankStatementLineJpa extends JpaRepository<BankStatementLine, UUID> {

    boolean existsByFingerprint(String fingerprint);

    Page<BankStatementLine> findByImportId(UUID importId, Pageable pageable);
}
