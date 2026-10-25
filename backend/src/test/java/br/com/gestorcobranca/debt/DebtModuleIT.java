package br.com.gestorcobranca.debt;

import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.ContactRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.ContractRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.DebtorRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.Outcome;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.TitleRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.UpsertResult;
import br.com.gestorcobranca.debt.application.port.in.DebtLedgerPort;
import br.com.gestorcobranca.debt.application.port.in.DebtLedgerPort.ComponentAmounts;
import br.com.gestorcobranca.debt.application.port.in.DebtLedgerPort.LedgerResult;
import br.com.gestorcobranca.debt.application.port.in.DebtLedgerPort.PaymentApplication;
import br.com.gestorcobranca.debt.application.port.in.DebtLedgerPort.PaymentReversal;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases.TitleSearch;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases.TitleView;
import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtorContactUseCases.ContactView;
import br.com.gestorcobranca.debt.application.port.in.DebtorSheetQueries;
import br.com.gestorcobranca.debt.application.port.in.DebtorSheetQueries.DebtorSummary;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases.DebtorView;
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
import br.com.gestorcobranca.shared.events.debt.TitleStatusChanged;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Fluxos críticos do módulo debt contra PostgreSQL real: carga idempotente, consolidação entre
 * credores, blind index, escopo por credor, razão (pagamento parcial e estorno) e append-only.
 */
@RecordApplicationEvents
class DebtModuleIT extends IntegrationTest {

    private static final String CPF = "529.982.247-25";

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
    DebtLedgerPort ledgerPort;
    @Autowired
    DebtorUseCases debtors;
    @Autowired
    DebtorContactUseCases contacts;
    @Autowired
    DebtTitleUseCases titles;
    @Autowired
    DebtorSheetQueries sheets;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    ApplicationEvents applicationEvents;

    UUID tenant;
    UUID creditorA;
    UUID creditorB;
    UUID portfolioA;
    UUID portfolioB;

    @BeforeEach
    void setUp() {
        String slug = "debt-" + UUID.randomUUID().toString().substring(0, 8);
        tenant = tenantExecution.callInTenant(Ids.newId(), "test.setup", () -> tenantAdministration
                .createTenant(new CreateTenantCommand(slug, "Assessoria Ltda", "Assessoria", "11.444.777/0001-61")).id());
        creditorA = system(() -> creditors.create(new CreditorCommand("Banco A S.A.", "Banco A", "11.222.333/0001-81", null, null)).id());
        creditorB = system(() -> creditors.create(new CreditorCommand("Loja B S.A.", "Loja B", "45.997.418/0001-53", null, null)).id());
        portfolioA = portfolio(creditorA, "A-01");
        portfolioB = portfolio(creditorB, "B-01");
    }

    private UUID portfolio(UUID creditorId, String code) {
        return system(() -> {
            UUID contract = contracts.create(new CreateContractCommand(creditorId, "CT-" + code,
                    new ContractTermsCommand(LocalDate.of(2026, 1, 1), null, null, 10, null))).id();
            return portfolios.create(new CreatePortfolioCommand(creditorId, contract, code, "Carteira " + code, null,
                    LocalDate.of(2026, 1, 1), null)).id();
        });
    }

    private <T> T system(Supplier<T> action) {
        return tenantExecution.callInTenant(tenant, "test", action);
    }

    private <T> T asUserOf(UUID creditorId, Supplier<T> action) {
        Actor user = new Actor(Actor.ActorType.USER, Ids.newId(), tenant, Ids.newId(), "operador",
                EnumSet.of(Permission.DEBTOR_VIEW, Permission.DEBTOR_MANAGE, Permission.DEBT_TITLE_MANAGE),
                CreditorScope.of(List.of(creditorId)));
        return tenantExecution.callAs(user, action);
    }

    private DebtorRecord debtorRecord(String name, ContactRecord... contactRecords) {
        return new DebtorRecord(CPF, name, LocalDate.of(1985, 3, 15), List.of(contactRecords));
    }

    private TitleRecord titleRecord(UUID creditorId, UUID portfolioId, UUID debtorId, String externalId, String principal,
                                    String reported) {
        return new TitleRecord(creditorId, portfolioId, debtorId, externalId,
                new ContractRecord("CTR-" + externalId, "Crediário", LocalDate.of(2025, 6, 1), new BigDecimal("3000.00")),
                1, LocalDate.of(2026, 3, 10), new BigDecimal(principal),
                reported == null ? null : new BigDecimal(reported), reported == null ? null : LocalDate.of(2026, 10, 1));
    }

    private long count(String table) {
        return system(() -> jdbcTemplate.queryForObject("select count(*) from debt." + table, Long.class));
    }

    @Test
    void reimportingTheSameRecordsDoesNotDuplicateAnything() {
        ContactRecord phone = new ContactRecord("PHONE", "(11) 98765-4321", "MOBILE", true);
        ContactRecord email = new ContactRecord("EMAIL", "Maria@Exemplo.com", null, false);
        UUID batch1 = Ids.newId();

        UpsertResult d1 = system(() -> ingestion.upsertDebtor(debtorRecord("Maria Silva", phone, email)));
        UpsertResult t1 = system(() -> ingestion.upsertTitle(titleRecord(creditorA, portfolioA, d1.id(), "T-1", "1000.00",
                "1000.00"), batch1));
        assertThat(d1.outcome()).isEqualTo(Outcome.CREATED);
        assertThat(t1.outcome()).isEqualTo(Outcome.CREATED);

        // mesmo arquivo reprocessado (outro formato do mesmo telefone, mesmo e-mail em maiúsculas)
        ContactRecord samePhone = new ContactRecord("PHONE", "+55 11 98765 4321", "MOBILE", true);
        ContactRecord sameEmail = new ContactRecord("EMAIL", "MARIA@EXEMPLO.COM", null, false);
        UpsertResult d2 = system(() -> ingestion.upsertDebtor(debtorRecord("Maria Silva", samePhone, sameEmail)));
        UpsertResult t2 = system(() -> ingestion.upsertTitle(titleRecord(creditorA, portfolioA, d1.id(), "T-1", "1000.00",
                "1000.00"), batch1));
        assertThat(d2).isEqualTo(new UpsertResult(d1.id(), Outcome.UNCHANGED, List.of()));
        assertThat(t2).isEqualTo(new UpsertResult(t1.id(), Outcome.UNCHANGED, List.of()));

        // nova carga com a mesma posição: nada muda
        UpsertResult t3 = system(() -> ingestion.upsertTitle(titleRecord(creditorA, portfolioA, d1.id(), "T-1", "1000.00",
                "1000.00"), Ids.newId()));
        assertThat(t3.outcome()).isEqualTo(Outcome.UNCHANGED);

        assertThat(count("debtor")).isEqualTo(1);
        assertThat(count("debtor_contact")).isEqualTo(2);
        assertThat(count("debt_title")).isEqualTo(1);
        assertThat(count("origin_contract")).isEqualTo(1);
        assertThat(count("title_ledger_entry")).isEqualTo(1);
        assertThat(count("title_balance_snapshot")).isEqualTo(1);
        assertThat(count("title_ownership")).isEqualTo(1);

        // carga posterior corrige o principal por ajuste no razão e registra novo saldo informado
        UpsertResult t4 = system(() -> ingestion.upsertTitle(titleRecord(creditorA, portfolioA, d1.id(), "T-1", "1100.00",
                "1150.00"), Ids.newId()));
        assertThat(t4.outcome()).isEqualTo(Outcome.UPDATED);
        DebtTitleUseCases.TitleBalanceView balance = system(() -> titles.balance(t1.id()));
        assertThat(balance.openBalance()).isEqualByComparingTo("1100.00");
        assertThat(balance.divergence()).isEqualByComparingTo("-50.00");
        assertThat(balance.snapshots()).hasSize(2);
        assertThat(count("title_ledger_entry")).isEqualTo(2);
    }

    @Test
    void invalidContactBecomesWarningWithoutBlockingTheDebtor() {
        UpsertResult result = system(() -> ingestion.upsertDebtor(debtorRecord("Maria",
                new ContactRecord("PHONE", "123", null, false), new ContactRecord("EMAIL", "maria@exemplo.com", null, false))));
        assertThat(result.outcome()).isEqualTo(Outcome.CREATED);
        assertThat(result.warnings()).containsExactly("contact.phone_invalid");
        assertThat(system(() -> contacts.list(result.id()))).singleElement()
                .satisfies(c -> assertThat(c.maskedValue()).isEqualTo("m***@exemplo.com"));
    }

    @Test
    void sameDebtorIsConsolidatedAcrossCreditorsAndFoundByDocument() {
        UUID debtorId = system(() -> ingestion.upsertDebtor(debtorRecord("Maria Silva")).id());
        system(() -> ingestion.upsertTitle(titleRecord(creditorA, portfolioA, debtorId, "A-1", "1000.00", null), Ids.newId()));
        // outro credor envia o mesmo CPF com outro nome: mesma pessoa
        UUID sameDebtor = system(() -> ingestion.upsertDebtor(new DebtorRecord("52998224725", "MARIA S", null, List.of())).id());
        assertThat(sameDebtor).isEqualTo(debtorId);
        system(() -> ingestion.upsertTitle(titleRecord(creditorB, portfolioB, debtorId, "B-1", "500.00", null), Ids.newId()));

        Long consolidated = system(() -> jdbcTemplate.queryForObject(
                "select count(*) from shared.business_event where event_type = 'debtor.consolidated' and debtor_id = ?",
                Long.class, debtorId));
        assertThat(consolidated).isEqualTo(1);

        DebtorView found = system(() -> debtors.findByDocument("529.982.247-25")).orElseThrow();
        assertThat(found.id()).isEqualTo(debtorId);
        assertThat(found.maskedDocument()).isEqualTo("***.982.247-**");
        assertThat(system(() -> debtors.search(CPF, null, null, PageQuery.of(0, 10)).content()))
                .extracting(DebtorView::id).containsExactly(debtorId);

        DebtorSummary summary = system(() -> sheets.summary(debtorId));
        assertThat(summary.creditors()).hasSize(2);
        assertThat(summary.titleCount()).isEqualTo(2);
        assertThat(summary.totalOpenBalance()).isEqualByComparingTo("1500.00");

        // documento gravado criptografado, nunca em claro
        String stored = system(() -> jdbcTemplate.queryForObject("select document_encrypted from debt.debtor where id = ?",
                String.class, debtorId));
        assertThat(stored).doesNotContain("52998224725").startsWith("k1v2:");
    }

    @Test
    void userScopedToCreditorAOnlySeesCreditorAData() {
        UUID debtorId = system(() -> ingestion.upsertDebtor(debtorRecord("Maria Silva")).id());
        UUID titleA = system(() -> ingestion.upsertTitle(titleRecord(creditorA, portfolioA, debtorId, "A-1", "1000.00", null),
                Ids.newId()).id());
        UUID titleB = system(() -> ingestion.upsertTitle(titleRecord(creditorB, portfolioB, debtorId, "B-1", "500.00", null),
                Ids.newId()).id());
        UUID onlyBDebtor = system(() -> {
            UUID id = ingestion.upsertDebtor(new DebtorRecord("11144477735", "João", null, List.of())).id();
            ingestion.upsertTitle(titleRecord(creditorB, portfolioB, id, "B-2", "200.00", null), Ids.newId());
            return id;
        });

        assertThat(asUserOf(creditorA, () -> titles.listByDebtor(debtorId))).extracting(TitleView::id).containsExactly(titleA);
        assertThat(asUserOf(creditorA, () -> titles.search(new TitleSearch(null, null, null, null, null, null, null, null),
                PageQuery.of(0, 50)).content())).extracting(TitleView::id).containsExactly(titleA);
        assertThatThrownBy(() -> asUserOf(creditorA, () -> titles.get(titleB))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> asUserOf(creditorA, () -> titles.suspend(titleB, "x")))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(asUserOf(creditorA, () -> debtors.search(null, null, null, PageQuery.of(0, 50)).content()))
                .extracting(DebtorView::id).containsExactly(debtorId);
        assertThatThrownBy(() -> asUserOf(creditorA, () -> debtors.get(onlyBDebtor)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(asUserOf(creditorA, () -> debtors.findByDocument("111.444.777-35"))).isEmpty();

        DebtorSummary summary = asUserOf(creditorA, () -> sheets.summary(debtorId));
        assertThat(summary.creditors()).extracting(DebtorSheetQueries.CreditorPosition::creditorId).containsExactly(creditorA);
        assertThat(summary.totalOpenBalance()).isEqualByComparingTo("1000.00");

        // revelar documento exige permissão específica
        assertThatThrownBy(() -> asUserOf(creditorA, () -> debtors.revealDocument(debtorId)))
                .isInstanceOf(ScopeAccessDeniedException.class);
    }

    @Test
    void partialPaymentAndReversalRestoreBalanceAndReopenTitle() {
        UUID debtorId = system(() -> ingestion.upsertDebtor(debtorRecord("Maria Silva")).id());
        UUID titleId = system(() -> ingestion.upsertTitle(titleRecord(creditorA, portfolioA, debtorId, "A-1", "1000.00", null),
                Ids.newId()).id());
        UUID p1 = Ids.newId();
        UUID p2 = Ids.newId();
        Instant paidAt = Instant.now();

        LedgerResult partial = system(() -> ledgerPort.applyPayment(new PaymentApplication(titleId, p1,
                ComponentAmounts.principalOnly(new BigDecimal("400.00")), paidAt)));
        assertThat(partial.applied()).isTrue();
        assertThat(partial.status()).isEqualTo("OPEN");
        assertThat(partial.openBalance()).isEqualByComparingTo("600.00");

        // reentrega do mesmo pagamento (webhook/evento repetido) não duplica
        LedgerResult repeated = system(() -> ledgerPort.applyPayment(new PaymentApplication(titleId, p1,
                ComponentAmounts.principalOnly(new BigDecimal("400.00")), paidAt)));
        assertThat(repeated.applied()).isFalse();
        assertThat(repeated.openBalance()).isEqualByComparingTo("600.00");

        LedgerResult settled = system(() -> ledgerPort.applyPayment(new PaymentApplication(titleId, p2,
                ComponentAmounts.principalOnly(new BigDecimal("600.00")), paidAt)));
        assertThat(settled.status()).isEqualTo("SETTLED");
        assertThat(settled.openBalance()).isEqualByComparingTo("0.00");

        LedgerResult reversed = system(() -> ledgerPort.reversePayment(new PaymentReversal(titleId, p2, paidAt.plusSeconds(3600),
                "Chargeback do cartão")));
        assertThat(reversed.previousStatus()).isEqualTo("SETTLED");
        assertThat(reversed.status()).isEqualTo("OPEN");
        assertThat(reversed.openBalance()).isEqualByComparingTo("600.00");
        assertThat(reversed.paidTotal()).isEqualByComparingTo("400.00");

        List<String> types = system(() -> titles.ledger(titleId, PageQuery.of(0, 50)).entries().content().stream()
                .map(DebtTitleUseCases.LedgerEntryView::entryType).toList());
        assertThat(types).containsExactly("OPENING", "PAYMENT", "PAYMENT", "REVERSAL");

        // abertura não publica; cada pagamento/estorno que altera saldo publica; a repetição não
        assertThat(applicationEvents.stream(TitleStatusChanged.class).filter(e -> e.titleId().equals(titleId)))
                .extracting(TitleStatusChanged::newStatus, e -> e.openBalance().toPlainString())
                .containsExactly(tuple("OPEN", "600.00"),
                        tuple("SETTLED", "0.00"),
                        tuple("OPEN", "600.00"));
        Long businessEvents = system(() -> jdbcTemplate.queryForObject(
                "select count(*) from shared.business_event where event_type = 'title.status_changed' and reference_id = ?",
                Long.class, titleId));
        assertThat(businessEvents).isEqualTo(2);
    }

    @Test
    void concurrentPaymentsOnTheSameTitleAreSerialized() throws Exception {
        UUID debtorId = system(() -> ingestion.upsertDebtor(debtorRecord("Maria Silva")).id());
        UUID titleId = system(() -> ingestion.upsertTitle(titleRecord(creditorA, portfolioA, debtorId, "A-1", "1000.00", null),
                Ids.newId()).id());
        int payments = 8;
        ExecutorService pool = Executors.newFixedThreadPool(payments);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<LedgerResult>> results = new ArrayList<>();
            for (int i = 0; i < payments; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return system(() -> ledgerPort.applyPayment(new PaymentApplication(titleId, Ids.newId(),
                            ComponentAmounts.principalOnly(new BigDecimal("125.00")), Instant.now())));
                }));
            }
            start.countDown();
            for (Future<LedgerResult> result : results) {
                assertThat(result.get(30, TimeUnit.SECONDS).applied()).isTrue();
            }
        } finally {
            pool.shutdownNow();
        }
        TitleView title = system(() -> titles.get(titleId));
        assertThat(title.openBalance()).isEqualByComparingTo("0.00");
        assertThat(title.paidTotal()).isEqualByComparingTo("1000.00");
        assertThat(title.status()).isEqualTo("SETTLED");
        assertThat(count("title_ledger_entry")).isEqualTo(payments + 1);
    }

    @Test
    void ledgerAndBalanceSnapshotsAreAppendOnlyInTheDatabase() {
        UUID debtorId = system(() -> ingestion.upsertDebtor(debtorRecord("Maria Silva")).id());
        system(() -> ingestion.upsertTitle(titleRecord(creditorA, portfolioA, debtorId, "A-1", "1000.00", "1000.00"),
                Ids.newId()));
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update("update debt.title_ledger_entry set amount = 1")))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update("delete from debt.title_ledger_entry")))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update("update debt.title_balance_snapshot set reported_balance = 0")))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void returnAndTransferKeepOwnershipHistory() {
        UUID debtorId = system(() -> ingestion.upsertDebtor(debtorRecord("Maria Silva")).id());
        UUID titleId = system(() -> ingestion.upsertTitle(titleRecord(creditorA, portfolioA, debtorId, "A-1", "1000.00", null),
                Ids.newId()).id());
        UUID portfolioA2 = portfolio(creditorA, "A-02");

        system(() -> titles.transferPortfolio(List.of(titleId), portfolioA2, "Reorganização de carteiras"));
        assertThatThrownBy(() -> system(() -> titles.transferPortfolio(List.of(titleId), portfolioB, "outro credor")))
                .hasFieldOrPropertyWithValue("code", "title.transfer_creditor_mismatch");
        TitleView returned = system(() -> titles.returnToCreditor(List.of(titleId), "Fim da vigência")).getFirst();
        assertThat(returned.status()).isEqualTo("RETURNED");

        List<DebtTitleUseCases.OwnershipView> history = system(() -> titles.ownershipHistory(titleId));
        assertThat(history).extracting(DebtTitleUseCases.OwnershipView::portfolioId).containsExactly(portfolioA, portfolioA2);
        assertThat(history).extracting(DebtTitleUseCases.OwnershipView::releaseType).containsExactly("TRANSFER", "RETURN");

        // título devolvido não é reaberto por nova carga
        UpsertResult reimport = system(() -> ingestion.upsertTitle(titleRecord(creditorA, portfolioA2, debtorId, "A-1",
                "1200.00", null), Ids.newId()));
        assertThat(reimport.outcome()).isEqualTo(Outcome.UNCHANGED);
        assertThat(reimport.warnings()).containsExactly("title.locked_status");
        assertThat(system(() -> titles.get(titleId)).principalAmount()).isEqualByComparingTo("1000.00");
    }

    @Test
    void manualContactsAreDeduplicatedAndRevealedOnlyWithPermission() {
        UUID debtorId = system(() -> debtors.register(new DebtorUseCases.RegisterDebtorCommand(CPF, "Maria", null, null)).id());
        ContactView phone = system(() -> contacts.add(debtorId, new DebtorContactUseCases.AddContactCommand("PHONE",
                "11 98765-4321", "MOBILE", true, null, null)));
        assertThat(phone.maskedValue()).isEqualTo("(11) *****-4321");
        assertThatThrownBy(() -> system(() -> contacts.add(debtorId, new DebtorContactUseCases.AddContactCommand("PHONE",
                "+5511987654321", null, false, null, null)))).hasFieldOrPropertyWithValue("code", "contact.duplicate");

        assertThat(system(() -> contacts.revealContact(debtorId, phone.id())).value()).isEqualTo("+5511987654321");
        Long views = system(() -> jdbcTemplate.queryForObject(
                "select count(*) from shared.access_log where event_type = 'SENSITIVE_DATA_VIEWED'", Long.class));
        assertThat(views).isEqualTo(1);

        system(() -> contacts.markInvalid(debtorId, phone.id(), "Número não pertence ao devedor"));
        assertThat(system(() -> contacts.contactsForChannel(debtorId, "PHONE"))).isEmpty();
    }

    @Test
    void inactiveDebtorQueryReturnsOnlyDebtorsWithoutTitlesInCollection() {
        UUID open = system(() -> ingestion.upsertDebtor(debtorRecord("Maria Silva")).id());
        system(() -> ingestion.upsertTitle(titleRecord(creditorA, portfolioA, open, "A-1", "100.00", null), Ids.newId()));
        UUID settled = system(() -> ingestion.upsertDebtor(new DebtorRecord("11144477735", "João", null, List.of())).id());
        UUID settledTitle = system(() -> ingestion.upsertTitle(titleRecord(creditorB, portfolioB, settled, "B-1", "50.00",
                null), Ids.newId()).id());
        system(() -> ledgerPort.applyPayment(new PaymentApplication(settledTitle, Ids.newId(),
                ComponentAmounts.principalOnly(new BigDecimal("50.00")), Instant.now())));
        UUID withoutTitles = system(() -> ingestion.upsertDebtor(new DebtorRecord("39053344705", "Ana", null, List.of())).id());
        system(() -> jdbcTemplate.update("update debt.debtor set created_at = now() - interval '2 years'"));
        system(() -> jdbcTemplate.update("update debt.debt_title set updated_at = now() - interval '2 years'"));
        Instant cutoff = Instant.now().minusSeconds(365L * 86400);

        List<DebtorUseCases.InactiveDebtor> found = system(() -> debtors.findInactiveDebtors(cutoff, null, 10));
        assertThat(found).extracting(DebtorUseCases.InactiveDebtor::debtorId).containsExactlyInAnyOrder(settled, withoutTitles);
        assertThat(found).filteredOn(d -> d.debtorId().equals(settled)).singleElement()
                .satisfies(d -> assertThat(d.creditorIds()).containsExactly(creditorB));
        // paginação por chave: o lote seguinte começa após o último id
        List<DebtorUseCases.InactiveDebtor> first = system(() -> debtors.findInactiveDebtors(cutoff, null, 1));
        List<DebtorUseCases.InactiveDebtor> next = system(() -> debtors.findInactiveDebtors(cutoff, first.getFirst().debtorId(), 10));
        assertThat(next).hasSize(1).extracting(DebtorUseCases.InactiveDebtor::debtorId)
                .doesNotContain(first.getFirst().debtorId());
        // movimentação recente tira o devedor da lista
        assertThat(system(() -> debtors.findInactiveDebtors(Instant.now().minusSeconds(3L * 365 * 86400), null, 10))).isEmpty();
        // exige escopo sobre todos os credores
        assertThatThrownBy(() -> asUserOf(creditorA, () -> debtors.findInactiveDebtors(cutoff, null, 10)))
                .isInstanceOf(ScopeAccessDeniedException.class);
    }

    @Test
    void contactFingerprintMatchesTheStoredContactIndex() {
        UUID debtorId = system(() -> ingestion.upsertDebtor(debtorRecord("Maria Silva",
                new ContactRecord("PHONE", "(11) 98765-4321", "MOBILE", true))).id());
        DebtorContactUseCases.ContactFingerprint fingerprint = system(() -> contacts.fingerprint("PHONE", "+55 11 98765 4321"));
        String stored = system(() -> jdbcTemplate.queryForObject(
                "select value_hash from debt.debtor_contact where debtor_id = ?", String.class, debtorId));
        assertThat(fingerprint.hash()).isEqualTo(stored);
        assertThat(fingerprint.maskedValue()).isEqualTo("(11) *****-4321");
        assertThatThrownBy(() -> system(() -> contacts.fingerprint("ADDRESS", "Rua A, 1")))
                .hasFieldOrPropertyWithValue("code", "contact.type_invalid");
    }

    @Test
    void anonymizationRemovesPersonalDataButKeepsTitles() {
        UUID debtorId = system(() -> ingestion.upsertDebtor(debtorRecord("Maria Silva",
                new ContactRecord("EMAIL", "maria@exemplo.com", null, false))).id());
        UUID titleId = system(() -> ingestion.upsertTitle(titleRecord(creditorA, portfolioA, debtorId, "A-1", "100.00", null),
                Ids.newId()).id());
        assertThatThrownBy(() -> system(() -> debtors.anonymize(debtorId, "Solicitação do titular")))
                .hasFieldOrPropertyWithValue("code", "debtor.has_open_titles");

        system(() -> ledgerPort.applyPayment(new PaymentApplication(titleId, Ids.newId(),
                ComponentAmounts.principalOnly(new BigDecimal("100.00")), Instant.now())));
        DebtorView anonymized = system(() -> debtors.anonymize(debtorId, "Solicitação do titular"));
        assertThat(anonymized.name()).isEqualTo("Titular anonimizado");
        assertThat(anonymized.maskedDocument()).isNull();
        assertThat(count("debtor_contact")).isZero();
        assertThat(system(() -> titles.get(titleId)).status()).isEqualTo("SETTLED");
        assertThat(system(() -> debtors.findByDocument(CPF))).isEmpty();

        Map<String, Object> row = system(() -> jdbcTemplate.queryForMap(
                "select document_encrypted, document_hash from debt.debtor where id = ?", debtorId));
        assertThat(row.values()).containsOnlyNulls();
    }
}
