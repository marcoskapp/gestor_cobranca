package br.com.gestorcobranca.payment.infrastructure.persistence;

import br.com.gestorcobranca.payment.domain.model.StatementImport;
import br.com.gestorcobranca.payment.domain.model.BankStatementLine;
import br.com.gestorcobranca.payment.domain.repository.BankStatementRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** Repositório de extratos sobre dois repositórios Spring Data (importação e linhas). */
@Component
class JpaBankStatementRepository implements BankStatementRepository {

    private final StatementImportJpa imports;
    private final BankStatementLineJpa lines;

    JpaBankStatementRepository(StatementImportJpa imports, BankStatementLineJpa lines) {
        this.imports = imports;
        this.lines = lines;
    }

    @Override
    public StatementImport save(StatementImport statementImport) {
        return imports.save(statementImport);
    }

    @Override
    public StatementImport saveAndFlush(StatementImport statementImport) {
        return imports.saveAndFlush(statementImport);
    }

    @Override
    public Optional<StatementImport> findImport(UUID id) {
        return imports.findById(id);
    }

    @Override
    public Optional<StatementImport> findImportForUpdate(UUID id) {
        return imports.lockById(id);
    }

    @Override
    public boolean existsImportBySha256(String sha256) {
        return imports.existsByFileSha256(sha256);
    }

    @Override
    public Page<StatementImport> imports(Pageable pageable) {
        return imports.findAll(pageable);
    }

    @Override
    public BankStatementLine save(BankStatementLine line) {
        return lines.save(line);
    }

    @Override
    public Optional<BankStatementLine> findLine(UUID id) {
        return lines.findById(id);
    }

    @Override
    public boolean existsLineByFingerprint(String fingerprint) {
        return lines.existsByFingerprint(fingerprint);
    }

    @Override
    public Page<BankStatementLine> lines(UUID importId, Pageable pageable) {
        return lines.findByImportId(importId, pageable);
    }
}
