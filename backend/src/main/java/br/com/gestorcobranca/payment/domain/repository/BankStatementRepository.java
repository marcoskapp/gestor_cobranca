package br.com.gestorcobranca.payment.domain.repository;

import br.com.gestorcobranca.payment.domain.model.StatementImport;
import br.com.gestorcobranca.payment.domain.model.BankStatementLine;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.UUID;

/** Importações de extrato e suas linhas. */
public interface BankStatementRepository {

    StatementImport save(StatementImport statementImport);

    StatementImport saveAndFlush(StatementImport statementImport);

    Optional<StatementImport> findImport(UUID id);

    Optional<StatementImport> findImportForUpdate(UUID id);

    boolean existsImportBySha256(String sha256);

    Page<StatementImport> imports(Pageable pageable);

    BankStatementLine save(BankStatementLine line);

    Optional<BankStatementLine> findLine(UUID id);

    boolean existsLineByFingerprint(String fingerprint);

    Page<BankStatementLine> lines(UUID importId, Pageable pageable);
}
