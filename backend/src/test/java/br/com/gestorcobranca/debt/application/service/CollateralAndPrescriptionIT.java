package br.com.gestorcobranca.debt.application.service;

import br.com.gestorcobranca.debt.application.port.in.CollateralUseCases;
import br.com.gestorcobranca.debt.application.port.in.CollateralUseCases.AppraiseCommand;
import br.com.gestorcobranca.debt.application.port.in.CollateralUseCases.CollateralView;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.CollateralAppraisalRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.CollateralRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.ContractNatureRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.ContractRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.DebtorRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.ObligationPartyRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.Outcome;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.TitleRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.UpsertResult;
import br.com.gestorcobranca.debt.application.port.in.DebtorSheetQueries;
import br.com.gestorcobranca.debt.application.port.in.DebtorSheetQueries.DebtorSummary;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
import br.com.gestorcobranca.debt.application.port.in.ObligationPartyUseCases;
import br.com.gestorcobranca.debt.application.port.in.ObligationPartyUseCases.PartyView;
import br.com.gestorcobranca.debt.application.port.in.OriginContractUseCases;
import br.com.gestorcobranca.debt.application.port.in.OriginContractUseCases.ClassifyContractCommand;
import br.com.gestorcobranca.debt.application.port.in.PrescriptionInterruptionPort;
import br.com.gestorcobranca.debt.application.port.in.PrescriptionInterruptionPort.InterruptionResult;
import br.com.gestorcobranca.debt.application.port.in.PrescriptionInterruptionPort.LegalInterruption;
import br.com.gestorcobranca.debt.application.port.in.PrescriptionInterruptionPort.StoredEvidence;
import br.com.gestorcobranca.debt.application.port.in.PrescriptionRuleUseCases;
import br.com.gestorcobranca.debt.application.port.in.PrescriptionRuleUseCases.RuleCommand;
import br.com.gestorcobranca.debt.application.port.in.PrescriptionRuleUseCases.RuleView;
import br.com.gestorcobranca.debt.application.port.in.TitlePrescriptionUseCases;
import br.com.gestorcobranca.debt.application.port.in.TitlePrescriptionUseCases.ClaimView;
import br.com.gestorcobranca.debt.application.port.in.TitlePrescriptionUseCases.EvidenceDownload;
import br.com.gestorcobranca.debt.application.port.in.TitlePrescriptionUseCases.EvidenceUpload;
import br.com.gestorcobranca.debt.application.port.in.TitlePrescriptionUseCases.PrescriptionView;
import br.com.gestorcobranca.debt.application.port.in.TitlePrescriptionUseCases.RegisterInterruptionCommand;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases.CreditorCommand;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases.CreatePortfolioCommand;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases.ContractTermsCommand;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases.CreateContractCommand;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration.CreateTenantCommand;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.events.debt.TitlePrescriptionApproaching;
import br.com.gestorcobranca.shared.jobs.JobContext;
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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Garantias, partes da obrigação, natureza do título e prescrição contra PostgreSQL real: carga
 * idempotente pela porta de ingestão, escopo por credor, append-only, regras semeadas da tabela
 * de mercado, interrupções (manual e pelo módulo legal), reconciliação e alerta diário.
 */
@RecordApplicationEvents
class CollateralAndPrescriptionIT extends IntegrationTest {

    private static final String CPF = "529.982.247-25";
    private static final String GUARANTOR_CPF = "111.444.777-35";

    @Autowired
    TenantExecution tenantExecution;
    @Autowired
    TenantAdministration tenantAdministration;
    @Autowired
    CreditorUseCases creditors;
    @Autowired
    ServiceContractUseCases serviceContracts;
    @Autowired
    PortfolioUseCases portfolios;
    @Autowired
    DebtIngestionPort ingestion;
    @Autowired
    CollateralUseCases collaterals;
    @Autowired
    ObligationPartyUseCases parties;
    @Autowired
    OriginContractUseCases contracts;
    @Autowired
    PrescriptionRuleUseCases rules;
    @Autowired
    TitlePrescriptionUseCases prescriptions;
    @Autowired
    PrescriptionInterruptionPort legalInterruptions;
    @Autowired
    DebtorSheetQueries sheets;
    @Autowired
    DebtorUseCases debtors;
    @Autowired
    PrescriptionAlertService alerts;
    @Autowired
    PrescriptionRecalculationJob recalculationJob;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    ApplicationEvents applicationEvents;

    UUID tenant;
    UUID creditorA;
    UUID creditorB;
    UUID portfolioA;
    LocalDate today;

    @BeforeEach
    void setUp() {
        today = LocalDate.now(TimeConfig.BUSINESS_ZONE);
        String slug = "guar-" + UUID.randomUUID().toString().substring(0, 8);
        tenant = tenantExecution.callInTenant(Ids.newId(), "test.setup", () -> tenantAdministration
                .createTenant(new CreateTenantCommand(slug, "Assessoria Ltda", "Assessoria", "11.444.777/0001-61")).id());
        creditorA = system(() -> creditors.create(new CreditorCommand("Banco A S.A.", "Banco A", "11.222.333/0001-81", null, null)).id());
        creditorB = system(() -> creditors.create(new CreditorCommand("Loja B S.A.", "Loja B", "45.997.418/0001-53", null, null)).id());
        portfolioA = system(() -> {
            UUID contract = serviceContracts.create(new CreateContractCommand(creditorA, "CT-A",
                    new ContractTermsCommand(LocalDate.of(2020, 1, 1), null, null, 10, null))).id();
            return portfolios.create(new CreatePortfolioCommand(creditorA, contract, "A-01", "Carteira A", null,
                    LocalDate.of(2020, 1, 1), null)).id();
        });
    }

    private <T> T system(Supplier<T> action) {
        return tenantExecution.callInTenant(tenant, "test", action);
    }

    private <T> T asUserOf(UUID creditorId, Supplier<T> action) {
        Actor user = new Actor(Actor.ActorType.USER, Ids.newId(), tenant, Ids.newId(), "juridico",
                EnumSet.of(Permission.DEBTOR_VIEW, Permission.COLLATERAL_MANAGE, Permission.PRESCRIPTION_MANAGE,
                        Permission.POLICY_MANAGE), CreditorScope.of(List.of(creditorId)));
        return tenantExecution.callAs(user, action);
    }

    private UUID debtor(String document, String name) {
        return system(() -> ingestion.upsertDebtor(new DebtorRecord(document, name, null, List.of())).id());
    }

    private UUID title(UUID debtorId, String externalId, String contractExternalId, LocalDate dueDate) {
        return system(() -> ingestion.upsertTitle(new TitleRecord(creditorA, portfolioA, debtorId, externalId,
                new ContractRecord(contractExternalId, "Financiamento", LocalDate.of(2019, 1, 10), new BigDecimal("30000.00")),
                1, dueDate, new BigDecimal("1000.00"), null, null), Ids.newId()).id());
    }

    private UUID contractOf(String contractExternalId) {
        return system(() -> jdbcTemplate.queryForObject(
                "select id from debt.origin_contract where external_id = ?", UUID.class, contractExternalId));
    }

    private ClaimView claim(PrescriptionView view, String claim) {
        return view.claims().stream().filter(c -> c.claim().equals(claim)).findFirst().orElseThrow();
    }

    private long count(String sql, Object... args) {
        return system(() -> jdbcTemplate.queryForObject(sql, Long.class, args));
    }

    // ---------------------------------------------------------------- regras

    @Test
    void marketDefaultsAreSeededPendingReviewAndOnlyOnce() {
        List<RuleView> seeded = system(() -> rules.applyMarketDefaults());
        assertThat(seeded).hasSize(12).allSatisfy(r -> assertThat(r.reviewStatus()).isEqualTo("PENDING_LEGAL_REVIEW"));
        assertThat(seeded).filteredOn(r -> r.instrumentType().equals("CHECK") && r.claim().equals("EXECUTION"))
                .singleElement().satisfies(r -> {
                    assertThat(r.termMonths()).isEqualTo(6);
                    assertThat(r.startOffsetDays()).isEqualTo(30);
                    assertThat(r.legalBasis()).contains("validar com o jurídico");
                });
        assertThat(system(() -> rules.applyMarketDefaults())).isEmpty();

        RuleView check = seeded.stream().filter(r -> r.instrumentType().equals("CHECK") && r.claim().equals("EXECUTION"))
                .findFirst().orElseThrow();
        RuleView validated = system(() -> rules.validate(check.id()));
        assertThat(validated.reviewStatus()).isEqualTo("VALIDATED");
        assertThat(validated.reviewedAt()).isNotNull();
        assertThatThrownBy(() -> system(() -> rules.create(new RuleCommand("CHECK", "EXECUTION", 6, "TITLE_DUE_DATE", 60,
                "FIRST_ONLY", 60, null)))).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "prescription_rule.duplicate");

        // regras valem para todos os credores: escopo restrito não altera
        assertThatThrownBy(() -> asUserOf(creditorA, () -> rules.update(check.id(), new RuleCommand("CHECK", "EXECUTION",
                6, "TITLE_DUE_DATE", 60, "FIRST_ONLY", 60, null)))).isInstanceOf(ScopeAccessDeniedException.class);
        // o recálculo após mudança de regra é enfileirado (pendente ou já executado pelo worker)
        assertThat(count("select count(*) from shared.processing_job where type = 'debt.prescription.recalculate'"
                + " and tenant_id = ?", tenant)).isGreaterThanOrEqualTo(1);
    }

    // ---------------------------------------------------------------- prescrição

    @Test
    void checkPrescriptionTracksExecutionAndCollectionClaimsWithInterruptions() {
        system(() -> rules.applyMarketDefaults());
        UUID debtorId = debtor(CPF, "Maria Silva");
        LocalDate due = LocalDate.of(2026, 1, 15);
        UUID titleId = title(debtorId, "CHQ-1", "CHQ-CTR-1", due);

        // contrato ainda como CONTRACT não executivo: só a pretensão de cobrança
        PrescriptionView initial = system(() -> prescriptions.get(titleId));
        assertThat(initial.instrumentType()).isEqualTo("CONTRACT");
        assertThat(initial.claims()).extracting(ClaimView::claim).containsExactly("COLLECTION");

        UpsertResult classified = system(() -> ingestion.classifyContract(
                new ContractNatureRecord(creditorA, "CHQ-CTR-1", "CHECK", true)));
        assertThat(classified.outcome()).isEqualTo(Outcome.UPDATED);
        assertThat(system(() -> ingestion.classifyContract(new ContractNatureRecord(creditorA, "CHQ-CTR-1", "CHECK", true)))
                .outcome()).isEqualTo(Outcome.UNCHANGED);

        PrescriptionView check = system(() -> prescriptions.get(titleId));
        assertThat(check.executiveTitle()).isTrue();
        assertThat(claim(check, "EXECUTION").estimatedDate()).isEqualTo(due.plusDays(30).plusMonths(6));
        assertThat(claim(check, "EXECUTION").ruleValidated()).isFalse();
        assertThat(claim(check, "COLLECTION").estimatedDate()).isEqualTo(due.plusMonths(60));
        assertThat(count("select count(*) from debt.title_prescription where title_id = ?", titleId)).isEqualTo(2);

        // protesto dentro do prazo reinicia as duas contagens, com evidência
        LocalDate protestDate = due.plusDays(60);
        Supplier<PrescriptionView> register = () -> prescriptions.registerInterruption(titleId,
                new RegisterInterruptionCommand("PROTEST", protestDate, "Protocolo 123", null),
                new EvidenceUpload("protesto.pdf", "application/pdf", 9, pdf()));
        PrescriptionView afterProtest = asUserOf(creditorA, register);
        assertThat(claim(afterProtest, "EXECUTION").estimatedDate()).isEqualTo(protestDate.plusMonths(6));
        assertThat(claim(afterProtest, "COLLECTION").estimatedDate()).isEqualTo(protestDate.plusMonths(60));
        assertThat(afterProtest.interruptions()).singleElement().satisfies(i -> {
            assertThat(i.effectiveFor()).containsExactlyInAnyOrder("EXECUTION", "COLLECTION");
            assertThat(i.evidence().fileName()).isEqualTo("protesto.pdf");
        });
        assertThatThrownBy(() -> asUserOf(creditorA, register)).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "prescription_interruption.duplicate");
        assertThatThrownBy(() -> asUserOf(creditorB, register)).isInstanceOf(ResourceNotFoundException.class);

        UUID interruptionId = afterProtest.interruptions().getFirst().id();
        EvidenceDownload download = asUserOf(creditorA, () -> prescriptions.evidence(titleId, interruptionId));
        try (InputStream content = download.content()) {
            assertThat(new String(content.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("%PDF-1.4\n");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }

        // regra de interrupção única: a citação posterior é registrada sem efeito
        InterruptionResult citation = system(() -> legalInterruptions.register(new LegalInterruption(titleId, "CITATION",
                protestDate.plusMonths(3), "0001234-56.2026.8.26.0100", null, Ids.newId(), null)));
        assertThat(citation.registered()).isTrue();
        assertThat(citation.collectionEstimatedDate()).isEqualTo(protestDate.plusMonths(60));
        InterruptionResult repeated = system(() -> legalInterruptions.register(new LegalInterruption(titleId, "CITATION",
                protestDate.plusMonths(3), "0001234-56.2026.8.26.0100", null, Ids.newId(), null)));
        assertThat(repeated.registered()).isFalse();
        assertThat(repeated.interruptionId()).isEqualTo(citation.interruptionId());
        assertThatThrownBy(() -> system(() -> legalInterruptions.register(new LegalInterruption(titleId, "PROTEST",
                today.minusDays(1), null, null, Ids.newId(),
                new StoredEvidence("tenants/" + Ids.newId() + "/evidences/x.pdf", "x.pdf", "application/pdf", 1, "ab")))))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "prescription_interruption.evidence_key_invalid");

        // interrupções são append-only no banco
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update(
                "update debt.prescription_interruption set reference = 'x' where id = ?", interruptionId)))
                .isInstanceOf(DataAccessException.class);

        // reclassificar como não executivo remove a pretensão executiva
        system(() -> contracts.classify(contractOf("CHQ-CTR-1"), new ClassifyContractCommand("CHECK", false)));
        assertThat(system(() -> prescriptions.get(titleId)).claims()).extracting(ClaimView::claim)
                .containsExactly("COLLECTION");
        Long audits = count("select count(*) from shared.audit_event where action = 'origin_contract.classified'");
        assertThat(audits).isEqualTo(2);
    }

    @Test
    void ruleChangesAreAppliedByRecalculationAndDeletionRemovesEstimates() {
        UUID debtorId = debtor(CPF, "Maria Silva");
        UUID titleId = title(debtorId, "T-1", "CTR-1", LocalDate.of(2025, 5, 10));
        // título anterior às regras: sem prescrição até o recálculo/reconciliação
        assertThat(system(() -> prescriptions.get(titleId)).claims()).isEmpty();
        assertThat(count("select count(*) from debt.title_prescription where title_id = ?", titleId)).isZero();

        RuleView rule = system(() -> rules.create(new RuleCommand("CONTRACT", "COLLECTION", 60, "TITLE_DUE_DATE", 0,
                "FIRST_ONLY", 90, "CC art. 206, §5º, I")));
        assertThat(rule.reviewStatus()).isEqualTo("VALIDATED");
        // o worker pode já ter executado o recálculo agendado pela regra; a reconciliação cobre o resto
        assertThat(tenantExecution.callInTenant(tenant, "test", alerts::reconcileBatch)).isBetween(0, 1);
        assertThat(count("select count(*) from debt.title_prescription where title_id = ?", titleId)).isEqualTo(1);
        assertThat(tenantExecution.callInTenant(tenant, "test", alerts::reconcileBatch)).isZero();

        system(() -> rules.update(rule.id(), new RuleCommand("CONTRACT", "COLLECTION", 36, "TITLE_DUE_DATE", 0,
                "FIRST_ONLY", 90, "prazo do credor")));
        // o job de recálculo (aqui executado diretamente; o worker pode executá-lo em paralelo) aplica o novo prazo
        system(() -> {
            recalculationJob.handle(new JobContext(Ids.newId(), tenant, PrescriptionRecalculationJob.TYPE, 1),
                    new PrescriptionRecalculationJob.Payload("CONTRACT", null));
            return null;
        });
        assertThat(claim(system(() -> prescriptions.get(titleId)), "COLLECTION").estimatedDate())
                .isEqualTo(LocalDate.of(2028, 5, 10));
        assertThat(system(() -> jdbcTemplate.queryForObject(
                "select estimated_date from debt.title_prescription where title_id = ?", LocalDate.class, titleId)))
                .isEqualTo(LocalDate.of(2028, 5, 10));

        system(() -> {
            rules.delete(rule.id(), "regra revista pelo jurídico");
            return null;
        });
        assertThat(count("select count(*) from debt.title_prescription where title_id = ?", titleId)).isZero();
        assertThat(system(() -> prescriptions.get(titleId)).claims()).isEmpty();
    }

    @Test
    void dailyScanAlertsOncePerEstimatedDateWithinTheRuleWindow() {
        system(() -> rules.applyMarketDefaults());
        UUID debtorId = debtor(CPF, "Maria Silva");
        // CONTRACT/COLLECTION: 60 meses, janela de 180 dias
        LocalDate near = today.minusMonths(60).plusDays(30);
        LocalDate far = today.minusMonths(60).plusDays(400);
        UUID nearTitle = title(debtorId, "N-1", "CTR-N", near);
        title(debtorId, "F-1", "CTR-F", far);

        int alerted = tenantExecution.callInTenant(tenant, "test", alerts::alertBatch);
        assertThat(alerted).isEqualTo(1);
        List<TitlePrescriptionApproaching> events = applicationEvents.stream(TitlePrescriptionApproaching.class).toList();
        assertThat(events).singleElement().satisfies(e -> {
            assertThat(e.titleId()).isEqualTo(nearTitle);
            assertThat(e.claim()).isEqualTo("COLLECTION");
            assertThat(e.instrumentType()).isEqualTo("CONTRACT");
            assertThat(e.estimatedDate()).isEqualTo(near.plusMonths(60));
            assertThat(e.ruleValidated()).isFalse();
            assertThat(e.openBalance()).isEqualByComparingTo("1000.00");
        });
        assertThat(count("select count(*) from shared.business_event where event_type = 'title.prescription_approaching'"))
                .isEqualTo(1);
        assertThat(tenantExecution.callInTenant(tenant, "test", alerts::alertBatch)).isZero();

        // reconhecimento da dívida reinicia a contagem: a nova data fica fora da janela
        system(() -> prescriptions.registerInterruption(nearTitle,
                new RegisterInterruptionCommand("ACKNOWLEDGEMENT", today, null, "acordo assinado"), null));
        assertThat(tenantExecution.callInTenant(tenant, "test", alerts::alertBatch)).isZero();
        assertThat(claim(system(() -> prescriptions.get(nearTitle)), "COLLECTION").estimatedDate())
                .isEqualTo(today.plusMonths(60));
    }

    // ---------------------------------------------------------------- garantias e partes

    @Test
    void collateralImportIsIdempotentAndLifecycleIsScopedAndAudited() {
        UUID debtorId = debtor(CPF, "Maria Silva");
        UUID t1 = title(debtorId, "V-1", "CTR-V", LocalDate.of(2026, 1, 10));
        title(debtorId, "V-2", "CTR-V", LocalDate.of(2026, 2, 10));
        CollateralRecord vehicle = new CollateralRecord(creditorA, "CTR-V", null, "VEHICLE_FIDUCIARY_ALIENATION", "Onix",
                "VEHICLE", "abc1d23", null, "9BWZZZ377VT004251", 2022, 2023, null, null, null, null, "DETRAN-SP", "G-998",
                LocalDate.of(2022, 3, 1), List.of("V-1"));

        UpsertResult created = system(() -> ingestion.upsertCollateral(vehicle));
        assertThat(created.outcome()).isEqualTo(Outcome.CREATED);
        assertThat(system(() -> ingestion.upsertCollateral(vehicle))).isEqualTo(new UpsertResult(created.id(),
                Outcome.UNCHANGED, List.of()));
        // mesma garantia reconhecida pelo chassi, com modalidade divergente: aviso, sem mudar o tipo
        UpsertResult mismatch = system(() -> ingestion.upsertCollateral(new CollateralRecord(creditorA, "CTR-V", null,
                "PLEDGE", "Onix", "VEHICLE", "ABC1D23", null, "9BWZZZ377VT004251", 2022, 2023, null, null, null, null,
                "DETRAN-SP", "G-998", LocalDate.of(2022, 3, 1), List.of())));
        assertThat(mismatch.id()).isEqualTo(created.id());
        assertThat(mismatch.outcome()).isEqualTo(Outcome.UPDATED);
        assertThat(mismatch.warnings()).containsExactly("collateral.type_mismatch");
        assertThatThrownBy(() -> system(() -> ingestion.upsertCollateral(new CollateralRecord(creditorA, "CTR-V", null,
                "OTHER", "Recebíveis", "OTHER", null, null, null, null, null, null, null, null, null, null, null, null,
                List.of())))).isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "collateral.external_id_required");

        UUID batch = Ids.newId();
        CollateralAppraisalRecord fipe = new CollateralAppraisalRecord(created.id(), new BigDecimal("62000.00"),
                today.minusDays(5), "FIPE", null);
        assertThat(system(() -> ingestion.recordCollateralAppraisal(fipe, batch)).outcome()).isEqualTo(Outcome.UPDATED);
        assertThat(system(() -> ingestion.recordCollateralAppraisal(fipe, batch)).outcome()).isEqualTo(Outcome.UNCHANGED);
        assertThat(system(() -> ingestion.recordCollateralAppraisal(fipe, Ids.newId())).outcome())
                .isEqualTo(Outcome.UNCHANGED);

        CollateralView manual = asUserOf(creditorA, () -> {
            collaterals.appraise(created.id(), new AppraiseCommand(new BigDecimal("58000.00"), today, "APPRAISER", "laudo"));
            return collaterals.changeStatus(created.id(), "UNDER_ENFORCEMENT", "busca e apreensão ajuizada");
        });
        assertThat(manual.status()).isEqualTo("UNDER_ENFORCEMENT");
        assertThat(manual.estimatedValue()).isEqualByComparingTo("58000.00");
        assertThat(manual.asset().plate()).isEqualTo("ABC1D23");
        assertThat(manual.titleIds()).isEmpty();
        assertThat(asUserOf(creditorA, () -> collaterals.appraisals(created.id()))).hasSize(2);
        assertThatThrownBy(() -> asUserOf(creditorA, () -> collaterals.changeStatus(created.id(), "CONSOLIDATED", "x")))
                .isInstanceOf(BusinessRuleException.class).hasFieldOrPropertyWithValue("code", "collateral.status_not_applicable");
        assertThatThrownBy(() -> asUserOf(creditorA, () -> collaterals.changeStatus(created.id(), "AUCTIONED", "pulo")))
                .isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("code", "collateral.invalid_transition");
        assertThatThrownBy(() -> asUserOf(creditorB, () -> collaterals.get(created.id())))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(count("select count(*) from shared.audit_event where entity_type = 'collateral' and entity_id = ?",
                created.id())).isGreaterThanOrEqualTo(4);
        assertThat(count("select count(*) from shared.business_event where event_type in "
                + "('collateral.registered', 'collateral.appraised', 'collateral.status_changed')")).isEqualTo(4);
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update(
                "update debt.collateral_appraisal set amount = 1 where collateral_id = ?", created.id())))
                .isInstanceOf(DataAccessException.class);
        assertThat(t1).isNotNull();
    }

    @Test
    void coObligorIsConsolidatedByDocumentAndVisibleThroughTheContractCreditor() {
        UUID debtorId = debtor(CPF, "Maria Silva");
        title(debtorId, "P-1", "CTR-P", LocalDate.of(2026, 1, 10));
        ObligationPartyRecord guarantor = new ObligationPartyRecord(creditorA, "CTR-P",
                new DebtorRecord(GUARANTOR_CPF, "João Avalista", null, List.of()), "GUARANTOR_AVAL", LocalDate.of(2019, 1, 10));

        UpsertResult party = system(() -> ingestion.upsertObligationParty(guarantor));
        assertThat(party.outcome()).isEqualTo(Outcome.CREATED);
        assertThat(system(() -> ingestion.upsertObligationParty(guarantor)).outcome()).isEqualTo(Outcome.UNCHANGED);
        assertThatThrownBy(() -> system(() -> ingestion.upsertObligationParty(new ObligationPartyRecord(creditorA, "CTR-P",
                new DebtorRecord(CPF, "Maria Silva", null, List.of()), "CO_DEBTOR", null))))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "obligation_party.principal_cannot_be_party");

        UUID contractId = contractOf("CTR-P");
        List<PartyView> list = asUserOf(creditorA, () -> parties.list(contractId));
        assertThat(list).extracting(PartyView::role).containsExactly("PRINCIPAL", "GUARANTOR_AVAL");
        UUID guarantorId = list.get(1).debtorId();
        assertThat(list.get(1).maskedDocument()).isEqualTo("***.444.777-**");

        // o avalista não tem título próprio, mas é visível a quem tem escopo sobre o credor do contrato
        DebtorSummary sheet = asUserOf(creditorA, () -> sheets.summary(guarantorId));
        assertThat(sheet.titleCount()).isZero();
        assertThat(sheet.coObligations()).singleElement().satisfies(c -> {
            assertThat(c.role()).isEqualTo("GUARANTOR_AVAL");
            assertThat(c.principalDebtorId()).isEqualTo(debtorId);
            assertThat(c.openBalance()).isEqualByComparingTo("1000.00");
        });
        assertThatThrownBy(() -> asUserOf(creditorB, () -> sheets.summary(guarantorId)))
                .isInstanceOf(ResourceNotFoundException.class);

        // coobrigado de contrato em cobrança não pode ser anonimizado
        assertThatThrownBy(() -> system(() -> debtors.anonymize(guarantorId, "pedido do titular")))
                .isInstanceOf(ConflictException.class).hasFieldOrPropertyWithValue("code", "debtor.has_open_titles");

        PartyView ended = asUserOf(creditorA, () -> parties.end(contractId, party.id(), today, "exoneração da fiança"));
        assertThat(ended.active()).isFalse();
        assertThat(system(() -> ingestion.upsertObligationParty(guarantor)).warnings())
                .containsExactly("obligation_party.ended");
        assertThatThrownBy(() -> asUserOf(creditorB, () -> parties.list(contractId)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> asUserOf(creditorB, () -> collaterals.register(contractId,
                new CollateralUseCases.CollateralCommand("OTHER", "G-1", "Bem", new CollateralUseCases.AssetCommand("OTHER",
                        null, null, null, null, null, null, null, null, null), null, Set.of()))))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private static InputStream pdf() {
        return new ByteArrayInputStream("%PDF-1.4\n".getBytes(StandardCharsets.UTF_8));
    }
}
