package br.com.gestorcobranca.payment;

import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.AgreementView;
import br.com.gestorcobranca.payment.application.port.in.PaymentInstructionUseCases.InstructionView;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.ExternalPaymentCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.PaymentView;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.TargetInput;
import br.com.gestorcobranca.payment.application.port.in.ReconciliationUseCases.StatementImportView;
import br.com.gestorcobranca.payment.application.port.in.ReconciliationUseCases.StatementUpload;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.jobs.JobDrain;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cancelamento do processamento do extrato (pedido do responsável; decisões 909–912) contra PostgreSQL real: o
 * cancelamento com o job rodando espera o lote em andamento e, depois dele, nenhum lote é gravado (barreira sob o lock
 * da importação); a importação presa em processamento sem job ativo é cancelada; a cancelada na fila nunca começa; a
 * nova tentativa depois do cancelamento retoma de onde parou, sem baixa duplicada; permissão e escopo por credor.
 */
class PaymentStatementCancelIT extends PaymentTestSupport {

    /** Chave do lock consultivo que segura o segundo lote no meio da gravação. */
    private static final long HOLD_KEY = 909_001L;
    /** Débitos entre os créditos: três lotes de 500 (cabeçalho = linha 1; a TED cai no terceiro). */
    private static final int DEBITS = 1_098;
    /** Linha do segundo lote (502–1001) cuja gravação fica presa no lock consultivo. */
    private static final int HELD_LINE = 600;
    /** Linha do segundo lote onde a falha forçada interrompe o processamento. */
    private static final int FAILING_LINE = 700;
    private static final int TOTAL_LINES = 2 + DEBITS + 3;

    @AfterEach
    void dropTriggers() throws SQLException {
        owner("""
                drop trigger if exists it_statement_line_hold on payment.statement_line;
                drop function if exists payment.it_statement_line_hold();
                drop trigger if exists it_statement_line_failure on payment.statement_line;
                drop function if exists payment.it_statement_line_failure();
                """);
    }

    @Test
    void cancelWhileTheJobIsRunningWaitsForTheCurrentChunkAndNothingIsWrittenAfterIt() throws Exception {
        provision("cancel-running");
        Fixture fixture = fixture();
        StatementImportView received = upload(fixture.csv(), null);
        holdOnLine(received.id(), HELD_LINE);

        try (Connection holder = ownerConnection(); Statement lock = holder.createStatement()) {
            lock.execute("select pg_advisory_lock(" + HOLD_KEY + ")");
            // o job roda numa thread do worker e fica preso no segundo lote, com a importação travada
            JobDrain.pollOnce(applicationContext);
            awaitTrue("segundo lote preso", () -> lines(received.id()) == 500 && waitingOnAdvisoryLock());
            assertThat(asFinance2(() -> reconciliation.getImport(received.id())).status()).isEqualTo("PROCESSING");

            CompletableFuture<StatementImportView> cancel = CompletableFuture.supplyAsync(() ->
                    asFinance2(() -> reconciliation.cancelImport(received.id(), "Extrato errado enviado")));
            // o cancelamento espera o lock da importação, que o lote em andamento segura
            awaitTrue("cancelamento aguardando o lote", this::waitingOnRowLock);
            assertThat(cancel).isNotDone();
            lock.execute("select pg_advisory_unlock(" + HOLD_KEY + ")");

            StatementImportView cancelled = cancel.get(30, TimeUnit.SECONDS);
            assertThat(cancelled.status()).isEqualTo("CANCELLED");
            // o lote em andamento confirmou antes do cancelamento; as contagens são as das linhas gravadas
            assertThat(cancelled.totalLines()).isEqualTo(1_000);
            assertThat(cancelled.createdPayments()).isEqualTo(1);
            assertThat(cancelled.finishedAt()).isNotNull();
        }

        settle();
        // o job percebeu, no lote seguinte, que não é mais dono: terminou sem gravar o terceiro lote nem concluir
        StatementImportView after = asFinance2(() -> reconciliation.getImport(received.id()));
        assertThat(after.status()).isEqualTo("CANCELLED");
        assertThat(after.totalLines()).isEqualTo(1_000);
        assertThat(lines(received.id())).isEqualTo(1_000);
        assertThat(job(received.id())).isEqualTo("DONE");
        assertThat(asFinance1(() -> payments.get(fixture.doc1().id())).status()).isEqualTo("CONFIRMED");
        assertThat(audits("statement_import.processed", received.id())).isZero();
        assertThat(audits("statement_import.failed", received.id())).isZero();
        assertThat(count("""
                select count(*) from shared.audit_event
                 where action = 'statement_import.cancelled' and entity_id = ? and actor_id = ?
                   and before_state ->> 'status' = 'PROCESSING' and after_state ->> 'status' = 'CANCELLED'
                   and reason = 'Extrato errado enviado'
                """, received.id(), finance2)).isEqualTo(1);

        // nova tentativa: retoma do terceiro lote, sem baixa duplicada
        dropTriggers();
        StatementImportView retried = asFinance2(() -> reconciliation.retryImport(received.id()));
        assertThat(retried.status()).isEqualTo("RECEIVED");
        assertThat(retried.retryCount()).isEqualTo(1);
        settle();
        assertProcessedWithoutDuplicates(received.id(), fixture);
        assertThat(count("""
                select count(*) from shared.audit_event
                 where action = 'statement_import.retried' and entity_id = ?
                   and before_state ->> 'status' = 'CANCELLED' and after_state ->> 'status' = 'RECEIVED'
                """, received.id())).isEqualTo(1);
    }

    @Test
    void importStuckInProcessingWithoutAnActiveJobIsCancelledAndRetriedWithoutDuplicatedSettlement() throws SQLException {
        provision("cancel-stuck");
        Fixture fixture = fixture();
        double cancelledBefore = cancelledImports();
        StatementImportView received = upload(fixture.csv(), null);
        failOnLine(received.id(), FAILING_LINE);
        settle();
        assertThat(asFinance2(() -> reconciliation.getImport(received.id())).status()).isEqualTo("PROCESSING");
        assertThat(lines(received.id())).isEqualTo(500);
        // o job morreu sem registrar a falha (decisão 894, pendência): a importação ficaria presa em processamento
        jdbcTemplate.update("""
                update shared.processing_job set status = 'DEAD', finished_at = now()
                 where type = 'payment.statement_import' and payload ->> 'importId' = ?
                """, received.id().toString());
        // "Tentar novamente" não serve para quem ainda está em processamento
        assertThatThrownBy(() -> asFinance2(() -> reconciliation.retryImport(received.id())))
                .isInstanceOf(ConflictException.class);

        StatementImportView cancelled = asFinance2(() -> reconciliation.cancelImport(received.id(), "Parado há horas"));
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.totalLines()).isEqualTo(500);
        assertThat(cancelled.createdPayments()).isEqualTo(1);
        assertThat(cancelledImports() - cancelledBefore).isEqualTo(1);
        // terminal: cancelar de novo → 409
        assertThatThrownBy(() -> asFinance2(() -> reconciliation.cancelImport(received.id(), "de novo")))
                .isInstanceOf(ConflictException.class);

        dropTriggers();
        asFinance2(() -> reconciliation.retryImport(received.id()));
        settle();
        assertProcessedWithoutDuplicates(received.id(), fixture);
        // terminou: não há mais o que cancelar
        assertThatThrownBy(() -> asFinance2(() -> reconciliation.cancelImport(received.id(), "tarde")))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void importCancelledWhileQueuedNeverStartsAndItsJobLeavesTheQueue() {
        provision("cancel-queued");
        StatementImportView received = upload(debitsOnly(3), null);
        StatementImportView cancelled = asFinance2(() -> reconciliation.cancelImport(received.id(), "Arquivo trocado"));
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.totalLines()).isZero();
        assertThat(job(received.id())).isEqualTo("CANCELLED");

        // o job que ainda chegasse a rodar também não a iniciaria (barreira): a fila fica vazia e nada é gravado
        settle();
        assertThat(asFinance2(() -> reconciliation.getImport(received.id())).status()).isEqualTo("CANCELLED");
        assertThat(lines(received.id())).isZero();

        asFinance2(() -> reconciliation.retryImport(received.id()));
        settle();
        StatementImportView processed = asFinance2(() -> reconciliation.getImport(received.id()));
        assertThat(processed.status()).isEqualTo("PROCESSED");
        assertThat(processed.totalLines()).isEqualTo(3);
        assertThat(processed.ignoredLines()).isEqualTo(3);
    }

    @Test
    void cancelRequiresThePermissionTheCreditorScopeAndAReason() {
        provision("cancel-scope");
        StatementImportView allCreditors = upload(debitsOnly(2), null);
        StatementImportView ofCreditorA = upload(debitsOnly(4), creditorA);

        // sem a permissão de conciliação
        assertThatThrownBy(() -> asOperator(() -> reconciliation.cancelImport(allCreditors.id(), "motivo")))
                .isInstanceOf(ScopeAccessDeniedException.class);
        // escopo restrito ao credor B: não enxerga a importação sem credor nem a do credor A
        assertThatThrownBy(() -> asFinanceOfB(() -> reconciliation.cancelImport(allCreditors.id(), "motivo")))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> asFinanceOfB(() -> reconciliation.cancelImport(ofCreditorA.id(), "motivo")))
                .isInstanceOf(ResourceNotFoundException.class);
        // motivo obrigatório e com limite
        assertThatThrownBy(() -> asFinance2(() -> reconciliation.cancelImport(ofCreditorA.id(), "  ")))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> asFinance2(() -> reconciliation.cancelImport(ofCreditorA.id(), "x".repeat(201))))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(audits("statement_import.cancelled", allCreditors.id())).isZero();
        assertThat(audits("statement_import.cancelled", ofCreditorA.id())).isZero();
        assertThat(asFinance2(() -> reconciliation.getImport(ofCreditorA.id())).status()).isEqualTo("RECEIVED");

        // quem tem o credor no escopo cancela
        StatementImportView cancelled = as(finance1, DefaultRoles.FINANCEIRO, CreditorScope.of(Set.of(creditorA)),
                () -> reconciliation.cancelImport(ofCreditorA.id(), "motivo"));
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(count("select count(*) from shared.audit_event where action = 'statement_import.cancelled' "
                + "and entity_id = ? and creditor_id = ?", ofCreditorA.id(), creditorA)).isEqualTo(1);
    }

    private void assertProcessedWithoutDuplicates(UUID importId, Fixture fixture) {
        StatementImportView processed = asFinance2(() -> reconciliation.getImport(importId));
        assertThat(processed.status()).isEqualTo("PROCESSED");
        assertThat(processed.totalLines()).isEqualTo(TOTAL_LINES);
        assertThat(processed.createdPayments()).isEqualTo(1);
        assertThat(processed.reconciledLines()).isEqualTo(2);
        assertThat(processed.divergentLines()).isEqualTo(3);
        assertThat(processed.ignoredLines()).isEqualTo(DEBITS);
        assertThat(lines(importId)).isEqualTo(TOTAL_LINES);
        // sem baixa duplicada: um só pagamento da instrução (primeiro lote) e a TED (terceiro) conciliada uma vez
        assertThat(count("select count(*) from payment.payment where instruction_id = ?", fixture.pix().id())).isEqualTo(1);
        assertThat(asFinance1(() -> payments.get(fixture.doc1().id())).status()).isEqualTo("RECONCILED");
        assertThat(count("select count(*) from payment.divergence where statement_line_id in "
                + "(select id from payment.statement_line where import_id = ?)", importId)).isEqualTo(3);
        assertThat(audits("statement_import.processed", importId)).isEqualTo(1);
        assertThat(job(importId)).isEqualTo("DONE");
    }

    private <T> T asFinanceOfB(java.util.function.Supplier<T> action) {
        return as(finance2, DefaultRoles.FINANCEIRO, CreditorScope.of(Set.of(creditorB)), action);
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

    /** Arquivo só com tarifas (débitos ignorados): o conteúdo muda com a quantidade, para não repetir o SHA-256. */
    private String debitsOnly(int count) {
        StringBuilder csv = new StringBuilder("data;valor;tipo;id_transacao;referencia;descricao\n");
        for (int i = 0; i < count; i++) {
            csv.append(today).append(";1,00;D;;;TARIFA ").append(UUID.randomUUID()).append('\n');
        }
        return csv.toString();
    }

    private StatementImportView upload(String csv, UUID creditorId) {
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
        StatementImportView view = asFinance2(() -> reconciliation.importStatement(new StatementUpload("CSV_GENERIC",
                creditorId, "extrato-cancelamento.csv", "text/csv", bytes.length, new ByteArrayInputStream(bytes))));
        assertThat(view.status()).isEqualTo("RECEIVED");
        return view;
    }

    /** Gatilho de teste: a gravação daquela linha espera o lock consultivo que o teste segura. */
    private void holdOnLine(UUID importId, int lineNumber) throws SQLException {
        owner("""
                create function payment.it_statement_line_hold() returns trigger language plpgsql as $$
                begin
                    if new.import_id = '%s' and new.line_number = %d then
                        perform pg_advisory_xact_lock(%d);
                    end if;
                    return new;
                end $$;
                create trigger it_statement_line_hold before insert on payment.statement_line
                    for each row execute function payment.it_statement_line_hold();
                """.formatted(importId, lineNumber, HOLD_KEY));
    }

    /** Gatilho de teste: a gravação daquela linha falha. */
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

    private boolean waitingOnAdvisoryLock() {
        Long waiting = jdbcTemplate.queryForObject("select count(*) from pg_locks where locktype = 'advisory' "
                + "and objid::bigint = ? and not granted", Long.class, HOLD_KEY);
        return waiting != null && waiting > 0;
    }

    /** Alguma transação esperando o lock de linha (o {@code for update} da importação). */
    private boolean waitingOnRowLock() {
        Long waiting = jdbcTemplate.queryForObject("select count(*) from pg_locks where locktype in "
                + "('transactionid', 'tuple') and not granted", Long.class);
        return waiting != null && waiting > 0;
    }

    private Connection ownerConnection() throws SQLException {
        String url;
        try (Connection c = jdbcTemplate.getDataSource().getConnection()) {
            url = c.getMetaData().getURL();
        }
        return DriverManager.getConnection(url, "app_owner", ownerPassword());
    }

    private void owner(String sql) throws SQLException {
        try (Connection owner = ownerConnection(); Statement statement = owner.createStatement()) {
            statement.execute(sql);
        }
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

    private long audits(String action, UUID importId) {
        return count("select count(*) from shared.audit_event where action = ? and entity_id = ?", action, importId);
    }

    private double cancelledImports() {
        var counter = meterRegistry.find("payment.reconciliation.imports").tag("status", "CANCELLED").counter();
        return counter == null ? 0 : counter.count();
    }
}
