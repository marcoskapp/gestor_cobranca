package br.com.gestorcobranca.payment;

import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.AgreementView;
import br.com.gestorcobranca.payment.application.port.in.PaymentInstructionUseCases.InstructionView;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.ExternalPaymentCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.PaymentView;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.TargetInput;
import br.com.gestorcobranca.payment.application.port.in.ReconciliationUseCases.StatementImportView;
import br.com.gestorcobranca.payment.application.port.in.ReconciliationUseCases.StatementUpload;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import br.com.gestorcobranca.shared.storage.FileStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Extrato processado em lotes com transação própria (decisão 868) contra PostgreSQL real: uma falha forçada no
 * segundo lote (gatilho no banco) desfaz só aquele lote — a baixa do primeiro já está confirmada —, a nova tentativa
 * pula as linhas gravadas sem baixa duplicada e mantém a impressão digital das linhas repetidas; esgotadas as
 * tentativas, a importação fica {@code FAILED} com as linhas já gravadas; a nova tentativa pedida por uma pessoa
 * (decisão do responsável à 873) retoma de onde parou e conclui sem baixa duplicada, e um arquivo com linha inválida
 * volta a {@code FAILED} apontando a linha.
 */
class PaymentStatementResumeIT extends PaymentTestSupport {

    @Autowired
    FileStorage fileStorage;
    @Autowired
    JobQueue jobQueue;

    /** Linhas de débito entre os créditos: empurram os últimos créditos para o segundo lote (500 linhas por lote). */
    private static final int DEBITS = 598;
    /** Número da última linha do arquivo (cabeçalho = 1), onde o gatilho de teste falha. */
    private static final int FAILING_LINE = 2 + 2 + DEBITS + 2;

    @AfterEach
    void dropFailureTrigger() throws SQLException {
        owner("""
                drop trigger if exists it_statement_line_failure on payment.statement_line;
                drop function if exists payment.it_statement_line_failure();
                """);
    }

    @Test
    void failureInTheSecondChunkKeepsTheFirstAndTheRetryResumesWithoutDuplicatedSettlement() throws SQLException {
        provision("resume");
        Fixture fixture = fixture();
        double resumedBefore = resumedLines();
        StatementImportView received = upload(fixture.csv());
        failOnLine(received.id(), FAILING_LINE);

        settle();
        StatementImportView interrupted = asFinance2(() -> reconciliation.getImport(received.id()));
        assertThat(interrupted.status()).isEqualTo("PROCESSING");
        assertThat(lines(received.id())).isEqualTo(500);
        // o primeiro lote já confirmou a baixa do Pix; o segundo (TED e depósito repetido) foi desfeito por inteiro
        assertThat(asOperator(() -> instructions.get(fixture.pix().id())).status()).isEqualTo("PAID");
        assertThat(asFinance1(() -> payments.get(fixture.doc1().id())).status()).isEqualTo("CONFIRMED");
        assertThat(job(received.id())).isEqualTo("PENDING");

        dropFailureTrigger();
        retryNow(received.id());
        settle();
        StatementImportView processed = asFinance2(() -> reconciliation.getImport(received.id()));
        assertThat(processed.status()).isEqualTo("PROCESSED");
        assertThat(processed.totalLines()).isEqualTo(FAILING_LINE - 1);
        assertThat(processed.createdPayments()).isEqualTo(1);
        assertThat(processed.reconciledLines()).isEqualTo(2);
        assertThat(processed.divergentLines()).isEqualTo(3);
        assertThat(processed.ignoredLines()).isEqualTo(DEBITS);
        assertThat(lines(received.id())).isEqualTo(FAILING_LINE - 1);
        // sem baixa duplicada: um só pagamento da instrução e a TED conciliada uma vez
        assertThat(count("select count(*) from payment.payment where instruction_id = ?", fixture.pix().id())).isEqualTo(1);
        assertThat(asFinance1(() -> payments.get(fixture.doc1().id())).status()).isEqualTo("RECONCILED");
        assertThat(count("select count(*) from shared.business_event where event_type = 'payment.reconciled' "
                + "and attributes ->> 'mode' = 'STATEMENT'")).isEqualTo(2);
        // o depósito repetido no segundo lote continua sendo a 2ª ocorrência, não uma duplicata do primeiro
        assertThat(count("select count(*) from payment.statement_line where import_id = ? and status = 'IGNORED_DUPLICATE'",
                received.id())).isZero();
        assertThat(count("select count(*) from payment.divergence where statement_line_id in "
                + "(select id from payment.statement_line where import_id = ?)", received.id())).isEqualTo(3);
        assertThat(count("select count(*) from shared.audit_event where action = 'statement_import.processed' "
                + "and entity_id = ?", received.id())).isEqualTo(1);
        assertThat(resumedLines() - resumedBefore).isEqualTo(500);
        assertThat(job(received.id())).isEqualTo("DONE");
    }

    @Test
    void exhaustedAttemptsLeaveTheImportFailedWithTheLinesAlreadyRecorded() throws SQLException {
        provision("resume-dead");
        Fixture fixture = fixture();
        StatementImportView received = upload(fixture.csv());
        failOnLine(received.id(), FAILING_LINE);

        settle();
        for (int attempt = 2; attempt <= 3; attempt++) {
            retryNow(received.id());
            settle();
        }
        StatementImportView failed = asFinance2(() -> reconciliation.getImport(received.id()));
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.failureCode()).isEqualTo("statement.processing_failed");
        assertThat(failed.totalLines()).isEqualTo(500);
        assertThat(failed.createdPayments()).isEqualTo(1);
        assertThat(failed.finishedAt()).isNotNull();
        assertThat(lines(received.id())).isEqualTo(500);
        assertThat(count("select count(*) from payment.payment where instruction_id = ?", fixture.pix().id())).isEqualTo(1);
        assertThat(count("select count(*) from shared.audit_event where action = 'statement_import.failed' "
                + "and entity_id = ?", received.id())).isEqualTo(1);
        assertThat(job(received.id())).isEqualTo("DEAD");
    }

    @Test
    void retryOfAFailedImportResumesFromWhereItStoppedWithoutDuplicatedSettlement() throws SQLException {
        provision("resume-retry");
        Fixture fixture = fixture();
        StatementImportView received = upload(fixture.csv());
        failOnLine(received.id(), FAILING_LINE);
        settle();
        for (int attempt = 2; attempt <= 3; attempt++) {
            retryNow(received.id());
            settle();
        }
        StatementImportView failed = asFinance2(() -> reconciliation.getImport(received.id()));
        assertThat(failed.status()).isEqualTo("FAILED");
        // parou no segundo lote: a primeira linha dele é a 502 (cabeçalho = 1, 500 linhas no primeiro lote)
        assertThat(failed.failureLine()).isEqualTo(502);
        assertThat(job(received.id())).isEqualTo("DEAD");

        // só quem importa extrato, só no escopo e só em FAILED
        assertThatThrownBy(() -> asOperator(() -> reconciliation.retryImport(received.id())))
                .isInstanceOf(ScopeAccessDeniedException.class);

        dropFailureTrigger();
        StatementImportView retried = asFinance2(() -> reconciliation.retryImport(received.id()));
        assertThat(retried.status()).isEqualTo("RECEIVED");
        assertThat(retried.failureCode()).isNull();
        assertThat(retried.failureLine()).isNull();
        assertThat(retried.retryCount()).isEqualTo(1);
        assertThatThrownBy(() -> asFinance2(() -> reconciliation.retryImport(received.id())))
                .isInstanceOf(ConflictException.class);

        // barreira por tentativa: um job da tentativa anterior (attempt 0) que ainda rodar não mexe na importação
        String retryKey = "statement:" + received.id() + ":retry:1";
        system(() -> jobQueue.cancelPending("payment.statement_import", retryKey));
        long auditBefore = count("select count(*) from shared.audit_event where entity_id = ?", received.id());
        system(() -> jobQueue.enqueue("payment.statement_import", Map.of("importId", received.id(), "attempt", 0), null,
                "it-stale:" + received.id()));
        settle();
        StatementImportView untouched = asFinance2(() -> reconciliation.getImport(received.id()));
        assertThat(untouched.status()).isEqualTo("RECEIVED");
        assertThat(untouched.startedAt()).isEqualTo(retried.startedAt());
        assertThat(lines(received.id())).isEqualTo(500);
        assertThat(count("select count(*) from shared.audit_event where entity_id = ?", received.id()))
                .isEqualTo(auditBefore);
        assertThat(job(received.id())).isEqualTo("DONE");
        // o job da tentativa 1 (recolocado na fila) é o que conclui
        system(() -> jobQueue.enqueue("payment.statement_import", Map.of("importId", received.id(), "attempt", 1), null,
                retryKey));

        settle();
        StatementImportView processed = asFinance2(() -> reconciliation.getImport(received.id()));
        assertThat(processed.status()).isEqualTo("PROCESSED");
        assertThat(processed.failureCode()).isNull();
        assertThat(processed.totalLines()).isEqualTo(FAILING_LINE - 1);
        assertThat(processed.createdPayments()).isEqualTo(1);
        assertThat(processed.reconciledLines()).isEqualTo(2);
        assertThat(processed.divergentLines()).isEqualTo(3);
        assertThat(lines(received.id())).isEqualTo(FAILING_LINE - 1);
        // sem baixa duplicada: um só pagamento da instrução e a TED conciliada uma vez
        assertThat(count("select count(*) from payment.payment where instruction_id = ?", fixture.pix().id())).isEqualTo(1);
        assertThat(asFinance1(() -> payments.get(fixture.doc1().id())).status()).isEqualTo("RECONCILED");
        assertThat(count("select count(*) from shared.business_event where event_type = 'payment.reconciled' "
                + "and attributes ->> 'mode' = 'STATEMENT'")).isEqualTo(2);
        assertThat(count("select count(*) from payment.divergence where statement_line_id in "
                + "(select id from payment.statement_line where import_id = ?)", received.id())).isEqualTo(3);
        // auditoria da nova tentativa com o antes e o depois
        assertThat(count("""
                select count(*) from shared.audit_event
                 where action = 'statement_import.retried' and entity_id = ? and actor_id = ?
                   and before_state ->> 'status' = 'FAILED' and after_state ->> 'status' = 'RECEIVED'
                   and before_state ->> 'failureCode' = 'statement.processing_failed'
                   and before_state ->> 'failureLine' = '502'
                """, received.id(), finance2)).isEqualTo(1);
        assertThat(count("select count(*) from shared.audit_event where action = 'statement_import.processed' "
                + "and entity_id = ?", received.id())).isEqualTo(1);
        assertThat(job(received.id())).isEqualTo("DONE");
        assertThatThrownBy(() -> asFinance2(() -> reconciliation.retryImport(received.id())))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void retryOfAFileWithAnInvalidLineFailsAgainPointingToTheLine() {
        provision("resume-invalid");
        StatementImportView received = upload("data;valor;tipo;id_transacao;referencia;descricao\n"
                + today + ";10,00;D;;;TARIFA\n"
                + "2026-13-01;20,00;C;;;CREDITO\n");
        settle();
        StatementImportView failed = asFinance2(() -> reconciliation.getImport(received.id()));
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.failureCode()).isEqualTo("statement.date_invalid");
        assertThat(failed.failureLine()).isEqualTo(3);
        assertThat(failed.totalLines()).isZero();

        asFinance2(() -> reconciliation.retryImport(received.id()));
        settle();
        StatementImportView again = asFinance2(() -> reconciliation.getImport(received.id()));
        assertThat(again.status()).isEqualTo("FAILED");
        assertThat(again.failureCode()).isEqualTo("statement.date_invalid");
        assertThat(again.failureLine()).isEqualTo(3);
        assertThat(again.retryCount()).isEqualTo(1);
        assertThat(lines(received.id())).isZero();
        assertThat(count("select count(*) from shared.audit_event where action = 'statement_import.failed' "
                + "and entity_id = ?", received.id())).isEqualTo(2);
        assertThat(count("select count(*) from shared.audit_event where action = 'statement_import.retried' "
                + "and entity_id = ?", received.id())).isEqualTo(1);
    }

    @Test
    void unreadableStoredFileOnTheLastAttemptAlsoLeavesTheImportFailed() {
        provision("resume-file");
        StatementImportView received = upload("data;valor;tipo;id_transacao;referencia;descricao\n"
                + today + ";10,00;D;;;TARIFA\n");
        // arquivo some do armazenamento depois do envio: a leitura falha antes de qualquer lote, nas 3 tentativas
        String key = system(() -> jdbcTemplate.queryForObject("select file_key from payment.statement_import where id = ?",
                String.class, received.id()));
        system(() -> {
            fileStorage.delete(key);
            return null;
        });
        settle();
        for (int attempt = 2; attempt <= 3; attempt++) {
            retryNow(received.id());
            settle();
        }
        StatementImportView failed = asFinance2(() -> reconciliation.getImport(received.id()));
        assertThat(failed.status()).isEqualTo("FAILED");
        assertThat(failed.failureCode()).isEqualTo("statement.processing_failed");
        assertThat(failed.totalLines()).isZero();
        assertThat(job(received.id())).isEqualTo("DEAD");
    }

    private record Fixture(InstructionView pix, PaymentView doc1, String csv) {
    }

    private Fixture fixture() {
        AgreementView agreement = formalizeAgreement(2);
        InstructionView pix = pixFor(agreement.id(), agreement.installments().getFirst().id(), "PIX_IMMEDIATE");
        PaymentView doc1 = asFinance1(() -> payments.registerExternal(new ExternalPaymentCommand(creditorB, debtor,
                new BigDecimal("100.00"), Instant.now().minus(2, ChronoUnit.HOURS), "TRANSFER", "AGENCY_ACCOUNT", "DOC-1",
                null, new TargetInput("TITLES", null, List.of(titleB), null)), pdf(), null));
        String day = today.toString();
        StringBuilder csv = new StringBuilder("data;valor;tipo;id_transacao;referencia;descricao\n");
        csv.append(day).append(';').append(pix.amount().toPlainString().replace('.', ',')).append(";C;;")
                .append(pix.txid()).append(";PIX RECEBIDO\n");
        csv.append(day).append(";55,00;C;;;DEPOSITO SEM REFERENCIA\n");
        for (int i = 0; i < DEBITS; i++) {
            csv.append(day).append(";1,00;D;;;TARIFA\n");
        }
        csv.append(day).append(";100,00;C;DOC-1;;TED RECEBIDA\n");
        csv.append(day).append(";55,00;C;;;DEPOSITO SEM REFERENCIA\n");
        csv.append(day).append(";33,00;C;;;CREDITO NAO IDENTIFICADO\n");
        return new Fixture(pix, doc1, csv.toString());
    }

    private StatementImportView upload(String csv) {
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
        StatementImportView view = asFinance2(() -> reconciliation.importStatement(new StatementUpload("CSV_GENERIC", null,
                "extrato-lotes.csv", "text/csv", bytes.length, new ByteArrayInputStream(bytes))));
        assertThat(view.status()).isEqualTo("RECEIVED");
        return view;
    }

    /** Gatilho de teste (como dono do esquema): a gravação daquela linha daquela importação falha. */
    private void failOnLine(UUID importId, int lineNumber) throws SQLException {
        owner("""
                create function payment.it_statement_line_failure() returns trigger language plpgsql as $$
                begin
                    if new.import_id = '%s' and new.line_number = %d then
                        raise exception 'falha forçada pelo teste';
                    end if;
                    return new;
                end $$;
                create trigger it_statement_line_failure before insert on payment.statement_line
                    for each row execute function payment.it_statement_line_failure();
                """.formatted(importId, lineNumber));
    }

    private void owner(String sql) throws SQLException {
        String url;
        try (Connection c = jdbcTemplate.getDataSource().getConnection()) {
            url = c.getMetaData().getURL();
        }
        try (Connection owner = DriverManager.getConnection(url, "app_owner", ownerPassword());
             Statement statement = owner.createStatement()) {
            statement.execute(sql);
        }
    }

    /** Antecipa a nova tentativa agendada com backoff. */
    private void retryNow(UUID importId) {
        jdbcTemplate.update("""
                update shared.processing_job set run_at = now()
                 where type = 'payment.statement_import' and status = 'PENDING' and payload ->> 'importId' = ?
                """, importId.toString());
    }

    /** Situação do job mais recente da importação (a nova tentativa enfileira outro). */
    private String job(UUID importId) {
        return jdbcTemplate.queryForObject("""
                select status from shared.processing_job
                 where type = 'payment.statement_import' and payload ->> 'importId' = ?
                 order by created_at desc limit 1
                """, String.class, importId.toString());
    }

    private long lines(UUID importId) {
        return count("select count(*) from payment.statement_line where import_id = ?", importId);
    }

    private double resumedLines() {
        var counter = meterRegistry.find("payment.reconciliation.lines_resumed").counter();
        return counter == null ? 0 : counter.count();
    }
}
