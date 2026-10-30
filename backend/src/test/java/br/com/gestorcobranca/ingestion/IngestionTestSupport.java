package br.com.gestorcobranca.ingestion;

import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases.TitleSearch;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases.TitleView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.ImportBatchView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.ImportIssueView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.UploadFileCommand;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases.CreditorCommand;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases.CreatePortfolioCommand;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases.ContractTermsCommand;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases.CreateContractCommand;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration.CreateTenantCommand;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.jobs.JobHandler;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import br.com.gestorcobranca.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** Cenário comum dos testes de integração da ingestão: tenant, dois credores e uma carteira por credor. */
abstract class IngestionTestSupport extends IntegrationTest {

    static final String JOB_TYPE = "ingestion.process-batch";
    static final String ANALYSIS_JOB_TYPE = "ingestion.review-analysis";
    static final String PDF_JOB_TYPE = "ingestion.pdf-map";
    static final String HEADER = "documento;nome;telefone_1;whatsapp_1;email_1;contrato;id_titulo;parcela;vencimento;"
            + "valor_principal;saldo;data_saldo;situacao";

    @Autowired
    TenantExecution tenantExecution;
    @Autowired
    TenantAdministration tenantAdministration;
    @Autowired
    CreditorUseCases creditors;
    @Autowired
    ServiceContractUseCases contracts;
    @Autowired
    PortfolioUseCases portfolios;
    @Autowired
    ImportBatchUseCases batches;
    @Autowired
    DebtTitleUseCases titles;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    JsonCodec json;
    @Autowired
    List<JobHandler<?>> jobHandlers;

    UUID tenant;
    UUID creditorA;
    UUID creditorB;
    UUID portfolioA;
    UUID portfolioB;

    @BeforeEach
    void setUpScenario() {
        String slug = "ing-" + UUID.randomUUID().toString().substring(0, 8);
        tenant = tenantExecution.callInTenant(Ids.newId(), "test.setup", () -> tenantAdministration
                .createTenant(new CreateTenantCommand(slug, "Assessoria Ltda", "Assessoria", "11.444.777/0001-61")).id());
        creditorA = system(() -> creditors.create(new CreditorCommand("Banco A S.A.", "Banco A", "11.222.333/0001-81",
                null, null)).id());
        creditorB = system(() -> creditors.create(new CreditorCommand("Loja B S.A.", "Loja B", "45.997.418/0001-53",
                null, null)).id());
        portfolioA = portfolio(creditorA, "A-01");
        portfolioB = portfolio(creditorB, "B-01");
    }

    UUID portfolio(UUID creditorId, String code) {
        return system(() -> {
            UUID contract = contracts.create(new CreateContractCommand(creditorId, "CT-" + code,
                    new ContractTermsCommand(LocalDate.of(2026, 1, 1), null, null, 10, null))).id();
            return portfolios.create(new CreatePortfolioCommand(creditorId, contract, code, "Carteira " + code, null,
                    LocalDate.of(2026, 1, 1), null)).id();
        });
    }

    <T> T system(Supplier<T> action) {
        return tenantExecution.callInTenant(tenant, "test", action);
    }

    /**
     * Usuário importador com escopo restrito ao credor. Jobs enfileirados pela ação são adiados na
     * mesma transação: o {@code JobWorker} de contextos de teste em cache não os executa, e o teste
     * controla cada fatia com {@link #runJobs(int)}.
     */
    <T> T asImporterOf(UUID creditorId, Supplier<T> action) {
        return asUser(Ids.newId(), EnumSet.of(Permission.IMPORT_VIEW, Permission.IMPORT_EXECUTE, Permission.DEBTOR_VIEW),
                creditorId, action);
    }

    /** Usuário com id e permissões definidos (segregação de funções), escopo restrito ao credor. */
    <T> T asUser(UUID userId, java.util.Set<Permission> permissions, UUID creditorId, Supplier<T> action) {
        Actor user = new Actor(Actor.ActorType.USER, userId, tenant, Ids.newId(), "usuario-" + userId.toString().substring(0, 4),
                EnumSet.copyOf(permissions), CreditorScope.of(List.of(creditorId)));
        return tenantExecution.callAs(user, () -> {
            T result = action.get();
            deferPendingJobs();
            return result;
        });
    }

    private void deferPendingJobs() {
        jdbcTemplate.update("""
                update shared.processing_job set run_at = now() + interval '1 day'
                where tenant_id = ? and type in (?, ?, ?) and status = 'PENDING'
                """, tenant, JOB_TYPE, ANALYSIS_JOB_TYPE, PDF_JOB_TYPE);
    }

    ImportBatchView uploadCsv(String fileName, String content, String mode) {
        return uploadCsv(fileName, content, mode, false, null);
    }

    ImportBatchView uploadCsv(String fileName, String content, String mode, boolean force, String reason) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return upload(portfolioA, null, fileName, bytes, mode, force, reason);
    }

    ImportBatchView upload(UUID portfolioId, UUID layoutId, String fileName, byte[] bytes, String mode, boolean force,
                           String reason) {
        return asImporterOf(creditorA, () -> batches.uploadFile(new UploadFileCommand(portfolioId, layoutId, mode, force,
                reason, fileName, "text/csv", bytes.length, () -> new java.io.ByteArrayInputStream(bytes))));
    }

    /**
     * Executa os jobs de importação pendentes do tenant como o {@code JobWorker}: reivindica, executa o
     * handler na transação do job e marca como concluído. Continua até esvaziar a fila (ou até
     * {@code maxJobs}).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    int runJobs(int maxJobs) {
        int executed = 0;
        while (executed < maxJobs) {
            List<Map<String, Object>> claimed = jdbcTemplate.queryForList("""
                    update shared.processing_job set status = 'RUNNING', attempts = attempts + 1, started_at = now()
                    where id = (select id from shared.processing_job
                                where tenant_id = ? and type in (?, ?, ?) and status = 'PENDING'
                                order by created_at limit 1 for update skip locked)
                    returning id, type, payload::text as payload, attempts
                    """, tenant, JOB_TYPE, ANALYSIS_JOB_TYPE, PDF_JOB_TYPE);
            if (claimed.isEmpty()) {
                break;
            }
            UUID id = (UUID) claimed.getFirst().get("id");
            String type = (String) claimed.getFirst().get("type");
            JobHandler handler = jobHandlers.stream().filter(h -> h.type().equals(type)).findFirst().orElseThrow();
            int attempt = ((Number) claimed.getFirst().get("attempts")).intValue();
            Object payload = json.fromJson((String) claimed.getFirst().get("payload"), handler.payloadType());
            tenantExecution.inTenant(tenant, "job:" + type, () -> {
                handler.handle(new JobContext(id, tenant, type, attempt), payload);
                // a próxima fatia, enfileirada nesta transação, também fica sob controle do teste
                deferPendingJobs();
                jdbcTemplate.update("update shared.processing_job set status = 'DONE', finished_at = now() where id = ?", id);
            });
            executed++;
        }
        return executed;
    }

    int runJobs() {
        return runJobs(10_000);
    }

    ImportBatchView batch(UUID id) {
        return asImporterOf(creditorA, () -> batches.get(id));
    }

    List<ImportIssueView> issues(UUID id) {
        return asImporterOf(creditorA, () -> batches.issues(id, null, PageQuery.of(0, 200)).content());
    }

    long titleCount(UUID creditorId) {
        return system(() -> titles.search(new TitleSearch(creditorId, null, null, null, null, null, null, null),
                PageQuery.of(0, 1)).totalElements());
    }

    List<TitleView> allTitles(UUID creditorId) {
        return system(() -> titles.search(new TitleSearch(creditorId, null, null, null, null, null, null, null),
                PageQuery.of(0, 200)).content());
    }

    TitleView title(UUID creditorId, String externalId) {
        return allTitles(creditorId).stream().filter(t -> t.externalId().equals(externalId)).findFirst().orElseThrow();
    }

    long count(String sql, Object... args) {
        return system(() -> jdbcTemplate.queryForObject(sql, Long.class, args));
    }

    /** CPF válido gerado a partir de um número sequencial (dígitos verificadores calculados). */
    static String cpf(int seed) {
        String base = "%09d".formatted(100_000_000 + seed);
        int d1 = digit(base, 10);
        int d2 = digit(base + d1, 11);
        return base + d1 + d2;
    }

    private static int digit(String digits, int weight) {
        int sum = 0;
        for (int i = 0; i < digits.length(); i++) {
            sum += (digits.charAt(i) - '0') * (weight - i);
        }
        int rest = sum % 11;
        return rest < 2 ? 0 : 11 - rest;
    }

    /** Linha do layout {@link #HEADER}. */
    static String line(String document, String name, String externalId, String principal, String situation) {
        return "%s;%s;(11) 98765-4321;S;;C-%s;%s;1;10/03/2026;%s;;;%s".formatted(document, name, externalId, externalId,
                principal, situation == null ? "" : situation);
    }

    static String generated(int rows) {
        StringBuilder csv = new StringBuilder(HEADER).append('\n');
        for (int i = 1; i <= rows; i++) {
            csv.append(line(cpf(i), "Devedor " + i, "G-" + i, "100,00", null)).append('\n');
        }
        return csv.toString();
    }
}
