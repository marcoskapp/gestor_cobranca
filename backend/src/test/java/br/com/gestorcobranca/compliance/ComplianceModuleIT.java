package br.com.gestorcobranca.compliance;

import br.com.gestorcobranca.compliance.application.port.in.ContactGate;
import br.com.gestorcobranca.compliance.application.port.in.ContactGate.ContactDecision;
import br.com.gestorcobranca.compliance.application.port.in.ContactGate.ContactRequest;
import br.com.gestorcobranca.compliance.application.port.in.ContactGate.DenialReason;
import br.com.gestorcobranca.compliance.application.port.in.ContactPolicyUseCases;
import br.com.gestorcobranca.compliance.application.port.in.ContactPolicyUseCases.ChannelRuleInput;
import br.com.gestorcobranca.compliance.application.port.in.ContactPolicyUseCases.PublishPolicyCommand;
import br.com.gestorcobranca.compliance.application.port.in.ContactRestrictionUseCases;
import br.com.gestorcobranca.compliance.application.port.in.ContactRestrictionUseCases.CreateRestrictionCommand;
import br.com.gestorcobranca.compliance.application.port.in.ContactRestrictionUseCases.RestrictionFilter;
import br.com.gestorcobranca.compliance.application.port.in.ContactRestrictionUseCases.RestrictionView;
import br.com.gestorcobranca.compliance.application.port.in.DataSubjectRequestUseCases;
import br.com.gestorcobranca.compliance.application.port.in.DataSubjectRequestUseCases.OpenRequestCommand;
import br.com.gestorcobranca.compliance.application.port.in.DataSubjectRequestUseCases.RequestView;
import br.com.gestorcobranca.compliance.application.port.in.FileContent;
import br.com.gestorcobranca.compliance.application.port.in.LegalBasisUseCases;
import br.com.gestorcobranca.compliance.application.port.in.LegalBasisUseCases.RegisterLegalBasisCommand;
import br.com.gestorcobranca.compliance.application.port.in.RetentionUseCases;
import br.com.gestorcobranca.compliance.application.port.in.RetentionUseCases.RetentionRunItemView;
import br.com.gestorcobranca.compliance.application.port.in.RetentionUseCases.RetentionRunView;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.ContactRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.DebtorRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.TitleRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtLedgerPort;
import br.com.gestorcobranca.debt.application.port.in.DebtLedgerPort.ComponentAmounts;
import br.com.gestorcobranca.debt.application.port.in.DebtLedgerPort.PaymentApplication;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
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
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.events.compliance.ContactRestrictionChanged;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import br.com.gestorcobranca.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fluxos críticos do módulo compliance contra PostgreSQL real: porteiro de contato com
 * restrições, políticas e concorrência; opt-out do provedor; solicitações de titulares
 * (acesso, oposição, anonimização com títulos em aberto); retenção; escopo por credor e
 * append-only.
 */
@RecordApplicationEvents
class ComplianceModuleIT extends IntegrationTest {

    private static final String CPF = "529.982.247-25";
    private static final String PHONE = "(11) 98765-4321";
    private static final List<String> ALL_DAYS = List.of("MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY",
            "SATURDAY", "SUNDAY");

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
    DebtIngestionPort ingestion;
    @Autowired
    DebtLedgerPort ledger;
    @Autowired
    DebtorUseCases debtors;
    @Autowired
    ContactGate gate;
    @Autowired
    ContactRestrictionUseCases restrictions;
    @Autowired
    ContactPolicyUseCases policies;
    @Autowired
    DataSubjectRequestUseCases requests;
    @Autowired
    LegalBasisUseCases legalBases;
    @Autowired
    RetentionUseCases retention;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    ApplicationEvents applicationEvents;

    UUID tenant;
    UUID creditorA;
    UUID creditorB;
    UUID portfolioA;
    UUID portfolioB;
    UUID debtor;
    UUID titleA;

    @BeforeEach
    void setUp() {
        String slug = "comp-" + UUID.randomUUID().toString().substring(0, 8);
        tenant = tenantExecution.callInTenant(Ids.newId(), "test.setup", () -> tenantAdministration
                .createTenant(new CreateTenantCommand(slug, "Assessoria Ltda", "Assessoria", "11.444.777/0001-61")).id());
        creditorA = system(() -> creditors.create(new CreditorCommand("Banco A S.A.", "Banco A", "11.222.333/0001-81", null, null)).id());
        creditorB = system(() -> creditors.create(new CreditorCommand("Loja B S.A.", "Loja B", "45.997.418/0001-53", null, null)).id());
        portfolioA = portfolio(creditorA, "A-01");
        portfolioB = portfolio(creditorB, "B-01");
        debtor = system(() -> ingestion.upsertDebtor(new DebtorRecord(CPF, "Maria Silva", LocalDate.of(1985, 3, 15),
                List.of(new ContactRecord("PHONE", PHONE, "MOBILE", true),
                        new ContactRecord("EMAIL", "maria@exemplo.com", null, false)))).id());
        titleA = title(creditorA, portfolioA, debtor, "A-1", "100.00");
        // janela de dia inteiro em todos os dias: os testes não dependem do horário em que rodam
        publishTenantPolicy(null);
    }

    private UUID portfolio(UUID creditorId, String code) {
        return system(() -> {
            UUID contract = contracts.create(new CreateContractCommand(creditorId, "CT-" + code,
                    new ContractTermsCommand(LocalDate.of(2026, 1, 1), null, null, 10, null))).id();
            return portfolios.create(new CreatePortfolioCommand(creditorId, contract, code, "Carteira " + code, null,
                    LocalDate.of(2026, 1, 1), null)).id();
        });
    }

    private UUID title(UUID creditorId, UUID portfolioId, UUID debtorId, String externalId, String principal) {
        return system(() -> ingestion.upsertTitle(new TitleRecord(creditorId, portfolioId, debtorId, externalId, null, 1,
                LocalDate.of(2026, 3, 10), new BigDecimal(principal), null, null), Ids.newId()).id());
    }

    private void publishTenantPolicy(Integer whatsappPerDay) {
        system(() -> policies.publish(new PublishPolicyCommand(null, null, "teste", List.of(
                new ChannelRuleInput("WHATSAPP", LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, ALL_DAYS, whatsappPerDay, null,
                        null, false),
                new ChannelRuleInput("PHONE", LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, ALL_DAYS, null, null, null, false),
                new ChannelRuleInput("EMAIL", LocalTime.MIDNIGHT, LocalTime.MIDNIGHT, ALL_DAYS, null, null, null, false)))));
    }

    private <T> T system(Supplier<T> action) {
        return tenantExecution.callInTenant(tenant, "test", action);
    }

    private <T> T asUserOf(UUID creditorId, Supplier<T> action) {
        Actor user = new Actor(Actor.ActorType.USER, Ids.newId(), tenant, Ids.newId(), "operador",
                EnumSet.of(Permission.DEBTOR_VIEW, Permission.CASE_WORK, Permission.CONTACT_RESTRICTION_MANAGE,
                        Permission.DATA_SUBJECT_REQUEST_MANAGE),
                CreditorScope.of(List.of(creditorId)));
        return tenantExecution.callAs(user, action);
    }

    private ContactDecision evaluate(UUID creditorId, String channel, String contactValue) {
        return system(() -> gate.evaluate(ContactRequest.of(debtor, creditorId, channel, contactValue, null, "OPERATOR")));
    }

    private CreateRestrictionCommand restriction(UUID creditorId, String channel, String type) {
        return new CreateRestrictionCommand(debtor, creditorId, channel, type, null, null, "Pedido registrado", "OPERATOR",
                null, null);
    }

    private long count(String sql, Object... args) {
        return system(() -> jdbcTemplate.queryForObject(sql, Long.class, args));
    }

    private static String read(FileContent file) {
        try (InputStream in = file.content()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ restrições e porteiro

    @Test
    void restrictionDeniesAndRevocationAllowsAgain() {
        assertThat(evaluate(creditorA, "WHATSAPP", null).allowed()).isTrue();

        RestrictionView created = system(() -> restrictions.create(restriction(null, null, "LEGAL_BLOCK")));
        ContactDecision denied = evaluate(creditorA, "WHATSAPP", null);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.reasons()).containsExactly(DenialReason.RESTRICTED_DEBTOR);
        assertThat(denied.nextAllowedAt()).isNull();

        system(() -> restrictions.revoke(created.id(), "Decisão judicial revogada"));
        assertThat(evaluate(creditorA, "WHATSAPP", null).allowed()).isTrue();

        assertThat(applicationEvents.stream(ContactRestrictionChanged.class)
                .filter(e -> e.restrictionId().equals(created.id())))
                .extracting(ContactRestrictionChanged::restricted).containsExactly(true, false);
        assertThat(count("select count(*) from shared.business_event where event_type = 'contact.restricted' and reference_id = ?",
                created.id())).isEqualTo(1);
        assertThat(count("select count(*) from shared.audit_event where entity_id = ? and action like 'contact_restriction.%'",
                created.id())).isEqualTo(2);
    }

    @Test
    void channelAndCreditorScopedRestrictionsOnlyAffectTheirScope() {
        system(() -> restrictions.create(restriction(creditorA, "EMAIL", "CREDITOR_REQUEST")));
        assertThat(evaluate(creditorA, "EMAIL", null).reasons()).containsExactly(DenialReason.RESTRICTED_CHANNEL);
        assertThat(evaluate(creditorA, "WHATSAPP", null).allowed()).isTrue();
        UUID titleB = title(creditorB, portfolioB, debtor, "B-1", "50.00");
        assertThat(titleB).isNotNull();
        assertThat(evaluate(creditorB, "EMAIL", null).allowed()).isTrue();
    }

    @Test
    void optOutFromProviderBlocksThatNumberAndIsIdempotent() {
        RestrictionView optOut = system(() -> restrictions.registerOptOutFromChannel(null, "WHATSAPP", "+55 11 98765-4321",
                "SAIR"));
        assertThat(optOut.type()).isEqualTo("OPT_OUT");
        assertThat(optOut.contactMasked()).isEqualTo("(11) *****-4321");
        assertThat(optOut.debtorId()).isNull();

        ContactDecision denied = evaluate(creditorA, "WHATSAPP", PHONE);
        assertThat(denied.reasons()).containsExactly(DenialReason.RESTRICTED_CONTACT_VALUE);
        // outro canal com o mesmo número e o mesmo canal sem informar o número continuam permitidos
        assertThat(evaluate(creditorA, "PHONE", PHONE).allowed()).isTrue();
        assertThat(evaluate(creditorA, "WHATSAPP", null).allowed()).isTrue();

        RestrictionView repeated = system(() -> restrictions.registerOptOutFromChannel(debtor, "WHATSAPP", PHONE, "PARAR"));
        assertThat(repeated.id()).isEqualTo(optOut.id());
        assertThat(count("select count(*) from compliance.contact_restriction where type = 'OPT_OUT'")).isEqualTo(1);
        assertThat(count("select count(*) from shared.business_event where event_type = 'contact.opt_out'")).isEqualTo(1);

        // o número nunca é gravado em claro
        String stored = system(() -> jdbcTemplate.queryForObject(
                "select contact_value_hash from compliance.contact_restriction where id = ?", String.class, optOut.id()));
        assertThat(stored).hasSize(64).doesNotContain("98765");
    }

    @Test
    void concurrentAuthorizationsNeverExceedTheDailyLimit() throws Exception {
        publishTenantPolicy(3);
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ContactDecision>> results = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                UUID reference = Ids.newId();
                results.add(pool.submit(() -> {
                    start.await();
                    return system(() -> gate.authorizeAndRecord(new ContactRequest(debtor, creditorA, "WHATSAPP", null,
                            null, "CAMPAIGN", "message", reference)));
                }));
            }
            start.countDown();
            int allowed = 0;
            for (Future<ContactDecision> result : results) {
                ContactDecision decision = result.get(30, TimeUnit.SECONDS);
                if (decision.allowed()) {
                    allowed++;
                    assertThat(decision.attemptId()).isNotNull();
                } else {
                    assertThat(decision.reasons()).containsExactly(DenialReason.DAILY_LIMIT);
                    assertThat(decision.attemptId()).isNull();
                }
            }
            assertThat(allowed).isEqualTo(3);
        } finally {
            pool.shutdownNow();
        }
        assertThat(count("select count(*) from compliance.contact_attempt where debtor_id = ?", debtor)).isEqualTo(3);
        assertThat(count("select count(*) from shared.business_event where event_type = 'contact.denied'")).isEqualTo(7);
        // evaluate só consulta: não registra tentativa
        assertThat(evaluate(creditorA, "WHATSAPP", null).reasons()).containsExactly(DenialReason.DAILY_LIMIT);
        assertThat(count("select count(*) from compliance.contact_attempt where debtor_id = ?", debtor)).isEqualTo(3);
    }

    @Test
    void contactAttemptsAreAppendOnly() {
        system(() -> gate.authorizeAndRecord(ContactRequest.of(debtor, creditorA, "PHONE", null, null, "OPERATOR")));
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update("update compliance.contact_attempt set origin = 'SYSTEM'")))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update("delete from compliance.contact_attempt")))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update("update compliance.contact_policy set notes = 'x'")))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void creditorScopeIsEnforced() {
        UUID onlyB = system(() -> {
            UUID id = ingestion.upsertDebtor(new DebtorRecord("11144477735", "João", null, List.of())).id();
            ingestion.upsertTitle(new TitleRecord(creditorB, portfolioB, id, "B-9", null, 1, LocalDate.of(2026, 3, 10),
                    new BigDecimal("10.00"), null, null), Ids.newId());
            return id;
        });
        system(() -> restrictions.create(new CreateRestrictionCommand(onlyB, creditorB, null, "LEGAL_BLOCK", null, null,
                "Ordem judicial", "LEGAL", null, null)));

        // comandos fora do escopo: 403; restrição para todos os credores exige escopo total
        assertThatThrownBy(() -> asUserOf(creditorA, () -> restrictions.create(restriction(creditorB, null, "LEGAL_BLOCK"))))
                .isInstanceOf(ScopeAccessDeniedException.class);
        assertThatThrownBy(() -> asUserOf(creditorA, () -> restrictions.create(restriction(null, null, "LEGAL_BLOCK"))))
                .isInstanceOf(ScopeAccessDeniedException.class);
        assertThatThrownBy(() -> asUserOf(creditorA, () -> gate.evaluate(ContactRequest.of(debtor, creditorB, "EMAIL", null,
                null, "OPERATOR")))).isInstanceOf(ScopeAccessDeniedException.class);
        // devedor só do credor B é invisível ao usuário do credor A
        assertThatThrownBy(() -> asUserOf(creditorA, () -> restrictions.search(new RestrictionFilter(onlyB, null, null,
                null, null), PageQuery.of(0, 10)))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> asUserOf(creditorA, () -> requests.open(new OpenRequestCommand("ACCESS", "EMAIL", "Maria",
                CPF, null, null, null)))).isInstanceOf(ScopeAccessDeniedException.class);

        RestrictionView own = asUserOf(creditorA, () -> restrictions.create(restriction(creditorA, "PHONE", "CREDITOR_REQUEST")));
        assertThat(asUserOf(creditorA, () -> restrictions.search(new RestrictionFilter(debtor, null, null, null, null),
                PageQuery.of(0, 10)).content())).extracting(RestrictionView::id).containsExactly(own.id());
        assertThat(asUserOf(creditorA, () -> gate.evaluate(ContactRequest.of(debtor, creditorA, "PHONE", null, null,
                "OPERATOR"))).reasons()).containsExactly(DenialReason.RESTRICTED_CHANNEL);
    }

    // ------------------------------------------------------------------ solicitações de titulares

    private RequestView openAndStart(String type) {
        return system(() -> {
            RequestView opened = requests.open(new OpenRequestCommand(type, "EMAIL", "Maria Silva", CPF, "maria@exemplo.com",
                    "Pedido do titular", null));
            requests.verifyIdentity(opened.id(), null);
            return requests.start(opened.id(), null);
        });
    }

    @Test
    void accessRequestGeneratesExportFileAndAccessEvent() {
        RequestView started = openAndStart("ACCESS");
        assertThat(started.debtorId()).isEqualTo(debtor);
        assertThat(started.maskedDocument()).isEqualTo("***.982.247-**");
        assertThat(started.dueAt()).isAfter(started.receivedAt().plusSeconds(14L * 86400));

        RequestView executed = system(() -> requests.execute(started.id()));
        assertThat(executed.hasResultFile()).isTrue();
        assertThat(executed.resultSha256()).hasSize(64);
        String content = system(() -> read(requests.openResult(started.id())));
        assertThat(content).contains("529.982.247-25", "+5511987654321", "maria@exemplo.com", "Banco A S.A.");

        assertThat(count("select count(*) from shared.access_log where event_type = 'DATA_EXPORTED' and details->>'requestId' = ?",
                started.id().toString())).isEqualTo(1);
        assertThat(count("select count(*) from shared.access_log where event_type = 'FILE_DOWNLOADED'")).isEqualTo(1);
        assertThat(count("select count(*) from shared.access_log where event_type = 'SENSITIVE_DATA_VIEWED'")).isEqualTo(3);

        RequestView completed = system(() -> requests.complete(started.id(), "Arquivo entregue ao titular por e-mail"));
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(count("select count(*) from shared.business_event where event_type = 'data_subject_request.completed'"))
                .isEqualTo(1);
        // dados do requerente gravados criptografados
        String storedName = system(() -> jdbcTemplate.queryForObject(
                "select requester_name_encrypted from compliance.data_subject_request where id = ?", String.class,
                started.id()));
        assertThat(storedName).startsWith("k1v2:").doesNotContain("Maria");
    }

    @Test
    void oppositionCreatesDebtorRequestRestriction() {
        RequestView started = openAndStart("OPPOSITION");
        system(() -> requests.execute(started.id()));
        ContactDecision decision = evaluate(creditorA, "EMAIL", null);
        assertThat(decision.reasons()).containsExactly(DenialReason.RESTRICTED_DEBTOR);
        List<RestrictionView> found = system(() -> restrictions.search(new RestrictionFilter(debtor, null, "DEBTOR_REQUEST",
                "ACTIVE", null), PageQuery.of(0, 10)).content());
        assertThat(found).singleElement().satisfies(r -> {
            assertThat(r.originRequestId()).isEqualTo(started.id());
            assertThat(r.source()).isEqualTo("DEBTOR");
            assertThat(r.creditorId()).isNull();
        });
        assertThatThrownBy(() -> system(() -> requests.execute(started.id())))
                .hasFieldOrPropertyWithValue("code", "dsr.already_executed");
    }

    @Test
    void anonymizationRequestIsRejectedWithOpenTitlesAndExecutedAfterSettlement() {
        RequestView first = openAndStart("ANONYMIZATION");
        RequestView rejected = system(() -> requests.execute(first.id()));
        assertThat(rejected.status()).isEqualTo("REJECTED");
        assertThat(rejected.responseSummary()).contains("1 título(s) em cobrança");
        assertThat(system(() -> debtors.get(debtor)).status()).isEqualTo("ACTIVE");
        assertThat(count("select count(*) from shared.audit_event where entity_id = ? and action = 'data_subject_request.rejected'",
                first.id())).isEqualTo(1);

        system(() -> ledger.applyPayment(new PaymentApplication(titleA, Ids.newId(),
                ComponentAmounts.principalOnly(new BigDecimal("100.00")), Instant.now())));
        RequestView second = openAndStart("ANONYMIZATION");
        RequestView executed = system(() -> requests.execute(second.id()));
        assertThat(executed.executedAt()).isNotNull();
        assertThat(system(() -> debtors.get(debtor)).status()).isEqualTo("ANONYMIZED");
        assertThat(system(() -> requests.complete(second.id(), "Dados eliminados")).status()).isEqualTo("COMPLETED");

        // devedor anonimizado não pode mais ser contatado
        assertThat(evaluate(creditorA, "PHONE", null).reasons()).containsExactly(DenialReason.DEBTOR_ANONYMIZED);
    }

    @Test
    void identityMustMatchTheRequesterDocument() {
        UUID other = system(() -> ingestion.upsertDebtor(new DebtorRecord("11144477735", "João", null, List.of())).id());
        RequestView opened = system(() -> requests.open(new OpenRequestCommand("ACCESS", "PORTAL", "Maria", CPF, null, null,
                null)));
        assertThatThrownBy(() -> system(() -> requests.verifyIdentity(opened.id(), other)))
                .hasFieldOrPropertyWithValue("code", "dsr.document_mismatch");
        assertThatThrownBy(() -> system(() -> requests.start(opened.id(), null)))
                .hasFieldOrPropertyWithValue("code", "dsr.invalid_transition");
    }

    // ------------------------------------------------------------------ retenção

    private void ageDebtor(UUID debtorId, String interval) {
        system(() -> {
            jdbcTemplate.update("update debt.debtor set created_at = now() - cast(? as interval) where id = ?", interval,
                    debtorId);
            return jdbcTemplate.update("update debt.debt_title set updated_at = now() - cast(? as interval) where debtor_id = ?",
                    interval, debtorId);
        });
    }

    @Test
    void retentionDryRunReportsAndRealRunAnonymizesRespectingLegalBasis() {
        // devedor com título em aberto: nunca é candidato
        ageDebtor(debtor, "40 months");
        // inativo sem títulos há 3 anos
        UUID inactive = system(() -> ingestion.upsertDebtor(new DebtorRecord("11144477735", "João", null, List.of())).id());
        ageDebtor(inactive, "36 months");
        // inativo com título quitado do credor B, cuja base legal exige 10 anos
        UUID retained = system(() -> ingestion.upsertDebtor(new DebtorRecord("39053344705", "Ana", null, List.of())).id());
        UUID titleB = title(creditorB, portfolioB, retained, "B-2", "20.00");
        system(() -> ledger.applyPayment(new PaymentApplication(titleB, Ids.newId(),
                ComponentAmounts.principalOnly(new BigDecimal("20.00")), Instant.now())));
        ageDebtor(retained, "30 months");
        system(() -> legalBases.register(new RegisterLegalBasisCommand(creditorB, null, "EXERCICIO_REGULAR_DIREITOS", null,
                "Contrato 2026/01", LocalDate.of(2020, 1, 1), null, "Dra. Jurídica", 120, null)));

        system(() -> retention.configure("DEBTOR_INACTIVE", 24, false));
        assertThatThrownBy(() -> system(() -> retention.run("DEBTOR_INACTIVE", false)))
                .hasFieldOrPropertyWithValue("code", "retention.policy_disabled");

        RetentionRunView dryRun = system(() -> retention.run("DEBTOR_INACTIVE", true));
        assertThat(dryRun.status()).isEqualTo("COMPLETED");
        assertThat(dryRun.candidateCount()).isEqualTo(2);
        assertThat(dryRun.eligibleCount()).isEqualTo(1);
        assertThat(dryRun.retainedCount()).isEqualTo(1);
        assertThat(dryRun.processedCount()).isZero();
        assertThat(system(() -> retention.runItems(dryRun.id(), PageQuery.of(0, 10)).content()))
                .extracting(RetentionRunItemView::debtorId, RetentionRunItemView::outcome)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(inactive, "ELIGIBLE"),
                        org.assertj.core.groups.Tuple.tuple(retained, "RETAINED"));
        assertThat(system(() -> debtors.get(inactive)).status()).isEqualTo("ACTIVE");

        system(() -> retention.configure("DEBTOR_INACTIVE", 24, true));
        RetentionRunView real = system(() -> retention.run("DEBTOR_INACTIVE", false));
        assertThat(real.processedCount()).isEqualTo(1);
        assertThat(real.failedCount()).isZero();
        assertThat(system(() -> debtors.get(inactive)).status()).isEqualTo("ANONYMIZED");
        assertThat(system(() -> debtors.get(retained)).status()).isEqualTo("ACTIVE");
        assertThat(system(() -> debtors.get(debtor)).status()).isEqualTo("ACTIVE");
        assertThat(count("select count(*) from shared.business_event where event_type = 'retention.run_completed'"))
                .isEqualTo(2);
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update("delete from compliance.retention_run_item")))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void legalBasisPeriodsDoNotOverlap() {
        system(() -> legalBases.register(new RegisterLegalBasisCommand(creditorA, null, "PROTECAO_CREDITO", "OPERADOR",
                "Contrato A", LocalDate.of(2026, 1, 1), null, "Jurídico A", 60, null)));
        assertThatThrownBy(() -> system(() -> legalBases.register(new RegisterLegalBasisCommand(creditorA, null,
                "LEGITIMO_INTERESSE", null, "Aditivo", LocalDate.of(2026, 6, 1), null, "Jurídico A", 60, null))))
                .hasFieldOrPropertyWithValue("code", "legal_basis.overlap");
        // carteira específica prevalece sobre o registro geral do credor
        system(() -> legalBases.register(new RegisterLegalBasisCommand(creditorA, portfolioA, "EXECUCAO_CONTRATO",
                "CONTROLADOR", "Cessão", LocalDate.of(2026, 1, 1), null, "Jurídico A", 72, null)));
        assertThat(system(() -> legalBases.findEffective(creditorA, portfolioA, LocalDate.of(2026, 7, 1))).orElseThrow()
                .legalBasis()).isEqualTo("EXECUCAO_CONTRATO");
        assertThat(system(() -> legalBases.findEffective(creditorA, null, null)).orElseThrow().processingRole())
                .isEqualTo("OPERADOR");
    }
}
