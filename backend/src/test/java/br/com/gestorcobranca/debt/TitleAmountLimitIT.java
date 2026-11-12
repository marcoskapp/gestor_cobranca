package br.com.gestorcobranca.debt;

import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.DebtorRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.TitleRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases;
import br.com.gestorcobranca.debt.application.port.in.TitleAmountAdjustmentUseCases;
import br.com.gestorcobranca.debt.application.port.in.TitleAmountAdjustmentUseCases.AdjustmentCommand;
import br.com.gestorcobranca.debt.application.port.in.TitleAmountAdjustmentUseCases.AdjustmentContext;
import br.com.gestorcobranca.debt.application.port.in.TitleAmountAdjustmentUseCases.AdjustmentView;
import br.com.gestorcobranca.debt.application.port.in.TitleAmountAdjustmentUseCases.SettingsView;
import br.com.gestorcobranca.debt.application.port.in.TitlePrescriptionUseCases.EvidenceUpload;
import br.com.gestorcobranca.debt.application.service.DebtTestHooks;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases.CreditorCommand;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases.CreatePortfolioCommand;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases.ContractTermsCommand;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases.CreateContractCommand;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration.CreateTenantCommand;
import br.com.gestorcobranca.pendingwork.application.port.in.PendingWorkUseCases;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.DomainException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import br.com.gestorcobranca.support.IntegrationTest;
import br.com.gestorcobranca.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * Limite da correção de valor contra PostgreSQL real, com o relógio de teste (decisões do responsável sobre as 864 e
 * 865): reduções aplicadas sem segunda pessoa no mesmo título somam na janela de 30 dias (a segunda redução pequena vira
 * aprovação; fora da janela volta a valer sozinha; dois pedidos simultâneos no mesmo título se enxergam pelo lock do
 * título) e a proposta de aumento do limite vence em 15 dias (confirmação
 * recusada, pendência some e o job diário descarta com auditoria).
 */
@Import(TitleAmountLimitIT.ClockConfig.class)
class TitleAmountLimitIT extends IntegrationTest {

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock titleAmountTestClock() {
            return new MutableClock();
        }
    }

    @Autowired
    MutableClock clock;
    @Autowired
    ApplicationContext applicationContext;
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
    DebtTitleUseCases titles;
    @Autowired
    TitleAmountAdjustmentUseCases adjustments;
    @Autowired
    PendingWorkUseCases pendingWork;
    @Autowired
    JdbcTemplate jdbc;

    UUID tenant;
    UUID creditor;
    UUID portfolio;
    UUID debtor;
    Actor gestor;
    Actor financeiro;

    @BeforeEach
    void setUp() {
        clock.reset();
        String slug = "adj-lim-" + UUID.randomUUID().toString().substring(0, 8);
        tenant = tenantExecution.callInTenant(Ids.newId(), "test.setup", () -> tenantAdministration
                .createTenant(new CreateTenantCommand(slug, "Assessoria Ltda", "Assessoria", "11.444.777/0001-61")).id());
        creditor = system(() -> creditors.create(new CreditorCommand("Banco A S.A.", "Banco A", "11.222.333/0001-81", null,
                null)).id());
        portfolio = system(() -> {
            UUID contract = contracts.create(new CreateContractCommand(creditor, "CT-LIM",
                    new ContractTermsCommand(LocalDate.of(2026, 1, 1), null, null, 10, null))).id();
            return portfolios.create(new CreatePortfolioCommand(creditor, contract, "LIM", "Carteira", null,
                    LocalDate.of(2026, 1, 1), null)).id();
        });
        debtor = system(() -> ingestion.upsertDebtor(new DebtorRecord("529.982.247-25", "Devedor Teste",
                LocalDate.of(1985, 3, 15), List.of(), null)).id());
        Set<Permission> both = EnumSet.of(Permission.DEBTOR_VIEW, Permission.TITLE_AMOUNT_ADJUST,
                Permission.TITLE_AMOUNT_APPROVE);
        gestor = new Actor(Actor.ActorType.USER, Ids.newId(), tenant, Ids.newId(), "Gestora", both, CreditorScope.ALL);
        financeiro = new Actor(Actor.ActorType.USER, Ids.newId(), tenant, Ids.newId(), "Financeiro", both,
                CreditorScope.ALL);
    }

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    private <T> T system(Supplier<T> action) {
        return tenantExecution.callInTenant(tenant, "test", action);
    }

    private <T> T as(Actor actor, Supplier<T> action) {
        return tenantExecution.callAs(actor, action);
    }

    private UUID title(String externalId, String principal) {
        return system(() -> ingestion.upsertTitle(new TitleRecord(creditor, portfolio, debtor, externalId, null, 1,
                LocalDate.of(2026, 3, 10), new BigDecimal(principal), null, null), Ids.newId())).id();
    }

    private static AdjustmentCommand principalTo(String value) {
        return new AdjustmentCommand(Map.of("PRINCIPAL", new BigDecimal(value)), "CALCULATION_ERROR",
                "Juros lançados em duplicidade no principal", null);
    }

    private static EvidenceUpload pdf() {
        byte[] content = "%PDF-1.4\n% evidencia de teste\n".getBytes(StandardCharsets.US_ASCII);
        return new EvidenceUpload("confirmacao.pdf", "application/pdf", content.length, new ByteArrayInputStream(content));
    }

    @Test
    void smallReductionsOnTheSameTitleAddUpWithinTheWindowAndCountAgainAfterIt() {
        UUID t = title("T-ACC", "1000.00");
        // limite padrão 200: a primeira redução pequena é aplicada na hora
        AdjustmentView first = as(gestor, () -> adjustments.request(t, principalTo("850.00"), null));
        assertThat(first.approvalMode()).isEqualTo("WITHIN_LIMIT");
        AdjustmentContext context = as(gestor, () -> adjustments.context(t));
        assertThat(context.recentReductions()).isEqualByComparingTo("150.00");
        assertThat(context.cumulativeWindowDays()).isEqualTo(30);

        // a segunda (100) caberia sozinha, mas 150 + 100 passa do limite: aprovação de outra pessoa, com evidência
        assertThatThrownBy(() -> as(gestor, () -> adjustments.request(t, principalTo("750.00"), null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("já aplicadas neste título");
        AdjustmentView second = as(gestor, () -> adjustments.request(t, principalTo("750.00"), pdf()));
        assertThat(second.status()).isEqualTo("PENDING_APPROVAL");
        assertThat(second.approvalMode()).isEqualTo("DUAL_CONTROL");
        assertThat(system(() -> titles.get(t)).openBalance()).isEqualByComparingTo("850.00");
        assertThat(count("select count(*) from shared.audit_event where action = 'title_amount.requested'"
                + " and entity_id = ? and metadata ->> 'cumulativeLimit' = 'true'", t)).isEqualTo(1);
        as(gestor, () -> adjustments.cancel(second.id(), "Vou pedir só o que cabe"));

        // o que ainda cabe no acumulado (50) é aplicado na hora; outro título não soma com este
        assertThat(as(gestor, () -> adjustments.request(t, principalTo("800.00"), null)).approvalMode())
                .isEqualTo("WITHIN_LIMIT");
        UUID other = title("T-OTHER", "1000.00");
        assertThat(as(gestor, () -> adjustments.request(other, principalTo("850.00"), null)).approvalMode())
                .isEqualTo("WITHIN_LIMIT");

        // fora da janela de 30 dias as reduções anteriores não contam mais
        clock.set(Instant.now().plus(Duration.ofDays(31)));
        assertThat(as(gestor, () -> adjustments.context(t)).recentReductions()).isEqualByComparingTo("0.00");
        AdjustmentView later = as(gestor, () -> adjustments.request(t, principalTo("650.00"), null));
        assertThat(later.status()).isEqualTo("APPLIED");
        assertThat(later.approvalMode()).isEqualTo("WITHIN_LIMIT");
        assertThat(system(() -> titles.get(t)).openBalance()).isEqualByComparingTo("650.00");
    }

    @Test
    void concurrentSmallReductionsOnTheSameTitleSeeEachOtherThroughTheTitleLock() throws Exception {
        UUID t = title("T-CONC", "1000.00");
        // multa de 200 criada por aumento aprovado por outra pessoa (não conta no acumulado)
        AdjustmentView fine = as(gestor, () -> adjustments.request(t, new AdjustmentCommand(
                Map.of("FINE", new BigDecimal("200.00")), "CONTRACT_TERMS", "Multa contratual não carregada", null), pdf()));
        as(financeiro, () -> adjustments.approve(fine.id(), "Conferido com o contrato"));
        assertThat(system(() -> titles.get(t)).openBalance()).isEqualByComparingTo("1200.00");

        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            // terceira transação segura o lock do título enquanto os dois pedidos chegam
            Future<?> holder = pool.submit(() -> system(() -> {
                jdbc.queryForList("select id from debt.debt_title where id = ? for update", t);
                locked.countDown();
                try {
                    if (!release.await(30, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("lock do título não foi liberado pelo teste");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return null;
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            // cada redução cabe sozinha no limite de 200; juntas (150 + 200) não
            Future<Object> principal = pool.submit(() -> outcome(gestor, () -> adjustments.request(t,
                    principalTo("850.00"), null)));
            Future<Object> fineDown = pool.submit(() -> outcome(financeiro, () -> adjustments.request(t,
                    new AdjustmentCommand(Map.of("FINE", new BigDecimal("0.00")), "CALCULATION_ERROR",
                            "Multa indevida", null), null)));
            awaitLockWaiters(2);
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);

            List<Object> outcomes = List.of(principal.get(30, TimeUnit.SECONDS), fineDown.get(30, TimeUnit.SECONDS));
            assertThat(outcomes).filteredOn(o -> o instanceof AdjustmentView).singleElement()
                    .satisfies(o -> {
                        assertThat(((AdjustmentView) o).status()).isEqualTo("APPLIED");
                        assertThat(((AdjustmentView) o).approvalMode()).isEqualTo("WITHIN_LIMIT");
                    });
            assertThat(outcomes).filteredOn(o -> o instanceof DomainException).singleElement()
                    .satisfies(o -> assertThat(((DomainException) o).code()).isEqualTo("title_amount.evidence_required"));
            assertThat(system(() -> titles.get(t)).openBalance()).isIn(new BigDecimal("1050.00"),
                    new BigDecimal("1000.00"));
            assertThat(count("select count(*) from debt.title_amount_adjustment where title_id = ?"
                    + " and approval_mode = 'WITHIN_LIMIT'", t)).isEqualTo(1);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /** Resultado do pedido: a visão ou a exceção de domínio (qualquer outra falha propaga). */
    private Object outcome(Actor actor, Supplier<AdjustmentView> request) {
        try {
            return as(actor, request);
        } catch (DomainException e) {
            return e;
        }
    }

    /** Espera (com prazo, sem sleep fixo como sincronização) as sessões bloqueadas no lock do título. */
    private void awaitLockWaiters(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (true) {
            Long waiting = jdbc.queryForObject("""
                    select count(*) from pg_stat_activity
                     where datname = current_database() and wait_event_type = 'Lock'
                       and query ilike '%debt_title%'
                    """, Long.class);
            if (waiting != null && waiting >= expected) {
                return;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("os pedidos não chegaram a esperar o lock do título: " + waiting);
            }
            Thread.sleep(50);
        }
    }

    @Test
    void thresholdProposalExpiresAfterFifteenDaysAndTheDailyJobDiscardsIt() {
        Instant proposedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        clock.set(proposedAt);
        SettingsView proposed = as(gestor, () -> adjustments.updateSettings(new BigDecimal("5000.00")));
        assertThat(proposed.proposalExpiresAt()).isEqualTo(proposedAt.plus(Duration.ofDays(15)));
        assertThat(proposed.proposalExpired()).isFalse();
        assertThat(thresholdPending(financeiro)).isEqualTo(1);
        // ainda no prazo, o job não mexe na proposta
        clock.set(proposedAt.plus(Duration.ofDays(14)));
        assertThat(DebtTestHooks.expireThresholdProposal(applicationContext, tenant)).isFalse();
        assertThat(as(financeiro, () -> adjustments.settings()).proposedThreshold()).isEqualByComparingTo("5000.00");

        // vencida: não é confirmada (409), some das pendências e a tela mostra vencida
        clock.set(proposedAt.plus(Duration.ofDays(15)).plusSeconds(1));
        assertThat(as(financeiro, () -> adjustments.settings()).proposalExpired()).isTrue();
        assertThat(thresholdPending(financeiro)).isZero();
        assertThatThrownBy(() -> as(financeiro, () -> adjustments.confirmSettings(new BigDecimal("5000.00"))))
                .isInstanceOf(ConflictException.class)
                .satisfies(e -> assertThat(((DomainException) e).code()).isEqualTo("title_amount.proposal_expired"));
        assertThat(as(financeiro, () -> adjustments.settings()).approvalThreshold()).isEqualByComparingTo("200.00");

        // o job diário descarta a proposta com auditoria; o limite vigente não muda
        assertThat(DebtTestHooks.expireThresholdProposal(applicationContext, tenant)).isTrue();
        SettingsView after = as(financeiro, () -> adjustments.settings());
        assertThat(after.proposedThreshold()).isNull();
        assertThat(after.proposalExpiresAt()).isNull();
        assertThat(after.approvalThreshold()).isEqualByComparingTo("200.00");
        assertThat(count("select count(*) from shared.audit_event where action = 'title_amount.settings_proposal_expired'",
                null)).isEqualTo(1);
        assertThat(DebtTestHooks.expireThresholdProposal(applicationContext, tenant)).isFalse();
        // nova proposta recomeça o prazo
        SettingsView again = as(gestor, () -> adjustments.updateSettings(new BigDecimal("5000.00")));
        assertThat(again.proposalExpired()).isFalse();
        assertThat(thresholdPending(financeiro)).isEqualTo(1);
    }

    private long thresholdPending(Actor actor) {
        return as(actor, () -> pendingWork.mine()).items().stream()
                .filter(i -> i.type().equals("debt.amount_threshold_proposal"))
                .mapToLong(PendingWorkUseCases.ItemView::count).sum();
    }

    private long count(String sql, UUID arg) {
        return system(() -> arg == null ? jdbc.queryForObject(sql, Long.class) : jdbc.queryForObject(sql, Long.class, arg));
    }
}
