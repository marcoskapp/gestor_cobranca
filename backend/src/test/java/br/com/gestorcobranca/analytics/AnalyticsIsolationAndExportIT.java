package br.com.gestorcobranca.analytics;

import br.com.gestorcobranca.analytics.application.port.in.AnalyticsExportUseCases;
import br.com.gestorcobranca.analytics.application.port.in.AnalyticsExportUseCases.ExportFile;
import br.com.gestorcobranca.analytics.application.port.in.AnalyticsQueries;
import br.com.gestorcobranca.analytics.application.port.in.AnalyticsRefreshUseCases;
import br.com.gestorcobranca.analytics.domain.model.ReportFilter;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Isolamento entre assessorias nas views e snapshots (RLS de quem consulta, com
 * {@code security_invoker}), atualização serializada e exportação (permissão, log de acesso,
 * proteção contra fórmulas).
 */
class AnalyticsIsolationAndExportIT extends AnalyticsTestSupport {

    static final List<String> SOURCE_VIEWS = List.of("src_creditor", "src_portfolio", "src_user", "src_team",
            "src_team_member", "src_title", "src_case", "src_interaction", "src_queue", "src_proposal",
            "src_agreement", "src_installment", "src_receipt", "src_payment_instruction", "src_divergence",
            "src_statement_import", "src_statement_line", "src_message", "src_call", "src_import_batch",
            "src_processing_job", "src_business_event", "src_performance_fact", "src_worked_case", "src_logged_time",
            "src_case_snapshot", "src_commission_statement");
    static final List<String> SNAPSHOTS = List.of("recovery_daily", "agreement_cohort", "portfolio_position",
            "case_position", "refresh_state");

    @Autowired
    AnalyticsQueries queries;
    @Autowired
    AnalyticsRefreshUseCases refresh;
    @Autowired
    AnalyticsExportUseCases exports;

    private UUID seed(String prefix, String creditorName, String amount) {
        provisionTenant(prefix);
        UUID[] creditor = new UUID[1];
        run(() -> {
            Facts f = new Facts();
            creditor[0] = f.creditor(creditorName, prefix);
            UUID portfolio = f.portfolio(creditor[0], prefix.toUpperCase());
            UUID debtor = f.debtor();
            f.title(creditor[0], portfolio, debtor, today().minusDays(20), "OPEN", amount);
            UUID c = f.collectionCase(debtor, creditor[0], portfolio, "OPEN", true, amount, amount, "QUEUE", null, null,
                    today().minusDays(20));
            UUID ag = f.agreement(creditor[0], debtor, portfolio, c, "HUMAN", "USER", operatorX, "PHONE",
                    AnalyticsTestSupport.at(2026, 6, 5, 10), amount, 1, "SETTLED", AnalyticsTestSupport.at(2026, 6, 6, 10),
                    null);
            f.receipt(creditor[0], portfolio, ag, amount, LocalDate.of(2026, 6, 6), 20);
            f.caseSnapshot(c, creditor[0], portfolio, operatorX, AnalyticsTestSupport.at(2026, 5, 1, 10), amount);
            f.performance(operatorX, LocalDate.of(2026, 6, 6), creditor[0], portfolio, "PHONE", 3, 1, 1, 60);
            f.deadJob(tenant, "job." + prefix);
        });
        as(manager, DefaultRoles.GESTOR, () -> refresh.refreshNow());
        return creditor[0];
    }

    private static ReportFilter june() {
        return new ReportFilter(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), null, null, null, null, null);
    }

    @Test
    void viewsAndSnapshotsKeepTenantIsolation() {
        seed("rls-a", "Credor A", "1000.00");
        UUID tenantA = tenant;
        UUID managerA = manager;
        seed("rls-b", "Credor B", "250.00");

        // cada assessoria vê só os próprios dados, nas views e nos snapshots
        assertThat(as(manager, DefaultRoles.GESTOR, () -> queries.recovery(june(), null)).totals().recoveredNet())
                .isEqualByComparingTo("250.00");
        assertThat(as(manager, DefaultRoles.GESTOR, () -> queries.portfolio(june())).totals().portfolioAmount())
                .isEqualByComparingTo("250.00");
        assertThat(as(manager, DefaultRoles.GESTOR, () -> queries.reliability(june())).jobs().deadByType())
                .extracting(AnalyticsQueries.CountRow::key).containsExactly("job.rls-b");
        for (String view : SOURCE_VIEWS) {
            assertThat(count("select count(*) from analytics." + view + " v where to_jsonb(v) ->> 'tenant_id' = ?",
                    tenantA.toString())).as(view).isZero();
        }
        for (String table : SNAPSHOTS) {
            assertThat(count("select count(*) from analytics." + table + " where tenant_id = ?", tenantA))
                    .as(table).isZero();
            assertThat(count("select count(*) from analytics." + table)).as(table).isPositive();
        }
        // sem assessoria na transação nada é visível (inclusive a fila global de jobs filtrada pela view)
        for (String view : SOURCE_VIEWS) {
            assertThat(jdbc.queryForObject("select count(*) from analytics." + view, Long.class)).as(view).isZero();
        }
        for (String table : SNAPSHOTS) {
            assertThat(jdbc.queryForObject("select count(*) from analytics." + table, Long.class)).as(table).isZero();
        }
        // gravação com a assessoria de outra é recusada; atualização sem assessoria também
        assertThatThrownBy(() -> run(() -> jdbc.update("""
                insert into analytics.recovery_daily (tenant_id, fact_date, creditor_id, channel, origin, origin_group,
                    delay_bucket, recovered_gross, reversed, receipts)
                values (?, date '2026-06-01', ?, 'PHONE', 'HUMAN', 'HUMAN', 'D0_30', 1, 0, 1)""", tenantA, managerA)))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("row-level security");
        assertThatThrownBy(() -> jdbc.queryForObject("select analytics.refresh_recovery_daily()", Long.class))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("assessoria");
        // o runtime não altera snapshots linha a linha
        assertThatThrownBy(() -> run(() -> jdbc.update("update analytics.recovery_daily set receipts = 0")))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void contractViewsRunAsInvokerAndProtectSourceColumns() throws SQLException {
        List<String> notInvoker = jdbc.queryForList("""
                select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = 'analytics' and c.relkind = 'v'
                  and not coalesce(c.reloptions @> array['security_invoker=true'], false)""", String.class);
        assertThat(notInvoker).isEmpty();
        List<String> views = jdbc.queryForList("""
                select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = 'analytics' and c.relkind = 'v' order by 1""", String.class);
        assertThat(views).containsExactlyInAnyOrderElementsOf(SOURCE_VIEWS);
        // nenhuma materialized view (não teria RLS)
        assertThat(jdbc.queryForObject("select count(*) from pg_matviews where schemaname = 'analytics'", Long.class))
                .isZero();
        // coluna usada pelo contrato não pode ser removida pelo módulo produtor sem tirar a view antes:
        // a migration falha (e a suíte com ela), em vez de o painel quebrar em produção
        String url;
        try (Connection c = jdbc.getDataSource().getConnection()) {
            url = c.getMetaData().getURL();
        }
        try (Connection owner = DriverManager.getConnection(url, "app_owner", ownerPassword());
             Statement st = owner.createStatement()) {
            owner.setAutoCommit(false);
            assertThatThrownBy(() -> st.execute("alter table settlement.receipt drop column competence_date"))
                    .isInstanceOf(SQLException.class).hasMessageContaining("depend");
            owner.rollback();
        }
        // nenhuma view expõe dado pessoal do devedor
        assertThat(jdbc.queryForList("""
                select table_name || '.' || column_name from information_schema.columns
                where table_schema = 'analytics' and column_name ~ '(document|email|phone|contact_value|note|name_encrypted)'
                """, String.class)).isEmpty();
    }

    /**
     * Valores de domínio de que as fórmulas dependem (docs/tecnico/desenho/analytics.md §3.2). Se o
     * produtor renomear ou remover um deles, este teste quebra e o contrato é revisto.
     */
    @Test
    void producerDomainsStillContainTheValuesTheFormulasUse() {
        Map<String, List<String>> required = Map.ofEntries(
                Map.entry("settlement.receipt:ck_receipt_kind", List.of("PAYMENT", "REVERSAL")),
                Map.entry("debt.debt_title:ck_debt_title_status", List.of("OPEN", "SUSPENDED")),
                Map.entry("collection.collection_case:ck_case_status", List.of("OPEN", "IN_NEGOTIATION", "PROMISE",
                        "AGREEMENT", "DISPUTED", "SUSPENDED", "LEGAL", "CLOSED")),
                Map.entry("collection.collection_case:ck_case_owner_type", List.of("QUEUE", "USER")),
                Map.entry("negotiation.agreement:ck_agreement_status", List.of("BROKEN")),
                Map.entry("negotiation.agreement_installment:ck_agreement_installment_status", List.of("PAID")),
                Map.entry("negotiation.proposal:ck_proposal_actor", List.of("USER", "ASSISTANT")),
                Map.entry("payment.payment_instruction:ck_instruction_status", List.of("FAILED")),
                Map.entry("payment.divergence:ck_divergence_status", List.of("OPEN")),
                Map.entry("payment.divergence:ck_divergence_type", List.of("DUPLICATE_PAYMENT")),
                Map.entry("payment.statement_import:ck_statement_import_status", List.of("FAILED")),
                Map.entry("payment.statement_line:ck_statement_line_status", List.of("IGNORED_DUPLICATE")),
                Map.entry("communication.message:ck_message_status", List.of("FAILED")),
                Map.entry("communication.message:ck_message_direction", List.of("OUTBOUND")),
                Map.entry("telephony.phone_call:ck_call_status", List.of("FAILED")),
                Map.entry("ingestion.import_batch:ck_import_batch_status", List.of("COMPLETED", "COMPLETED_WITH_ERRORS",
                        "FAILED", "CANCELLED")),
                Map.entry("ingestion.import_batch:ck_import_batch_mode", List.of("IMPORT")),
                Map.entry("shared.processing_job:ck_processing_job_status", List.of("DONE", "DEAD")),
                Map.entry("incentive.commission_statement:ck_statement_status", List.of("APPROVED", "EXPORTED")));
        required.forEach((key, values) -> {
            String[] parts = key.split(":");
            String definition = jdbc.queryForObject("""
                    select pg_get_constraintdef(oid) from pg_constraint
                    where conrelid = ?::regclass and conname = ?""", String.class, parts[0], parts[1]);
            assertThat(definition).as(key).isNotNull();
            values.forEach(v -> assertThat(definition).as(key).contains("'" + v + "'"));
        });
        // toda origem de acordo do negotiation tem grupo (humano, assistente, portal, campanha)
        String origins = jdbc.queryForObject("""
                select pg_get_constraintdef(oid) from pg_constraint
                where conrelid = 'negotiation.agreement'::regclass and conname = 'ck_agreement_origin'""", String.class);
        List<String> values = java.util.regex.Pattern.compile("'([A-Z_]+)'").matcher(origins).results()
                .map(m -> m.group(1)).distinct().toList();
        assertThat(values).hasSizeGreaterThanOrEqualTo(7);
        for (String origin : values) {
            assertThat(jdbc.queryForObject("select analytics.origin_group(?)", String.class, origin)).as(origin)
                    .isNotEqualTo("OTHER");
        }
        assertThat(jdbc.queryForObject("select analytics.delay_bucket(361)", String.class)).isEqualTo("D361_PLUS");
        assertThat(jdbc.queryForObject("select analytics.delay_bucket(0)", String.class)).isEqualTo("NOT_DUE");
    }

    @Test
    void exportNeutralizesFormulasAndIsLogged() throws Exception {
        seed("exp", "=HYPERLINK(\"http://x\")", "1000.00");
        ExportFile csv = as(manager, DefaultRoles.GESTOR, () -> exports.export("recovery", june(), "CREDITOR", "csv"));
        byte[] bytes = csv.content().readAllBytes();
        assertThat(bytes[0]).isEqualTo((byte) 0xEF);
        String text = new String(bytes, StandardCharsets.UTF_8);
        assertThat(text).contains("secao;chave;rotulo;indicador;valor;numerador;denominador;observacao");
        assertThat(text).contains("'=HYPERLINK");
        assertThat(text).doesNotContain(";=HYPERLINK");
        assertThat(text).contains("recuperado_liquido;1000.00");
        assertThat(csv.fileName()).isEqualTo("relatorio-recovery-2026-06-01-a-2026-06-30.csv");
        assertThat(csv.sha256()).hasSize(64);

        ExportFile xlsx = as(manager, DefaultRoles.GESTOR, () -> exports.export("portfolio", june(), null, "XLSX"));
        List<String> labels = new ArrayList<>();
        try (XSSFWorkbook book = new XSSFWorkbook(xlsx.content())) {
            for (Row row : book.getSheetAt(0)) {
                if (row.getCell(2) != null) {
                    labels.add(row.getCell(2).getStringCellValue());
                }
                for (int c = 0; c < row.getLastCellNum(); c++) {
                    if (row.getCell(c) != null) {
                        assertThat(row.getCell(c).getCellType()).isNotEqualTo(org.apache.poi.ss.usermodel.CellType.FORMULA);
                    }
                }
            }
        }
        assertThat(labels).contains("'=HYPERLINK(\"http://x\")");

        List<Map<String, Object>> logs = system(() -> jdbc.queryForList("""
                select details ->> 'report' as report, details ->> 'format' as format, details ->> 'sha256' as sha
                from shared.access_log where event_type = 'DATA_EXPORTED' and actor_id = ?
                  and details ->> 'resource' = 'analytics_report' order by occurred_at""", manager));
        assertThat(logs).extracting(m -> m.get("report") + "/" + m.get("format"))
                .containsExactly("recovery/CSV", "portfolio/XLSX");
        assertThat(logs).extracting(m -> m.get("sha")).containsExactly(csv.sha256(), xlsx.sha256());

        // sem REPORT_EXPORT (supervisor), recusado e registrado
        assertThatThrownBy(() -> as(supervisor, DefaultRoles.SUPERVISOR, () -> exports.export("recovery", june(), null,
                "CSV"))).isInstanceOf(ScopeAccessDeniedException.class);
        // exportar exige também a permissão de leitura do relatório
        assertThatThrownBy(() -> asWith(manager, EnumSet.of(Permission.REPORT_EXPORT), null,
                () -> exports.export("recovery", june(), null, "CSV"))).isInstanceOf(ScopeAccessDeniedException.class);
        // operador exporta o próprio desempenho só se tiver REPORT_EXPORT; com ela, continua restrito a si
        ExportFile own = asWith(operatorX, EnumSet.of(Permission.REPORT_EXPORT, Permission.PERFORMANCE_VIEW_OWN), null,
                () -> exports.export("productivity", june(), "OPERATOR", "CSV"));
        String ownText = new String(own.content().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(ownText).contains("Operador X").doesNotContain("Operador Y").contains("restrito_ao_escopo");
        assertThatThrownBy(() -> as(manager, DefaultRoles.GESTOR, () -> exports.export("recovery", june(), null, "PDF")))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> as(manager, DefaultRoles.GESTOR, () -> exports.export("outro", june(), null, "CSV")))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(Set.of(csv.contentType())).containsExactly("text/csv; charset=UTF-8");
    }
}
