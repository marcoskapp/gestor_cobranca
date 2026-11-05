package br.com.gestorcobranca.debt;

import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.DebtorRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.TitleRecord;
import br.com.gestorcobranca.debt.application.port.in.DebtIngestionPort.UpsertResult;
import br.com.gestorcobranca.debt.application.port.in.DebtLedgerPort;
import br.com.gestorcobranca.debt.application.port.in.DebtLedgerPort.ComponentAmounts;
import br.com.gestorcobranca.debt.application.port.in.DebtLedgerPort.PaymentApplication;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases;
import br.com.gestorcobranca.debt.application.port.in.TitleAmountAdjustmentUseCases;
import br.com.gestorcobranca.debt.application.port.in.TitleAmountAdjustmentUseCases.AdjustmentCommand;
import br.com.gestorcobranca.debt.application.port.in.TitleAmountAdjustmentUseCases.AdjustmentView;
import br.com.gestorcobranca.debt.application.port.in.TitleAmountAdjustmentUseCases.DivergenceView;
import br.com.gestorcobranca.debt.application.port.in.TitleAmountAdjustmentUseCases.SettingsView;
import br.com.gestorcobranca.debt.application.port.in.TitlePrescriptionUseCases.EvidenceUpload;
import br.com.gestorcobranca.debt.application.port.spi.TitleAgreementGuard;
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
import br.com.gestorcobranca.shared.domain.Ids;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Correção manual do valor do título contra PostgreSQL real: dentro do limite aplica na hora; acima, segunda pessoa
 * aprova (nunca quem pediu); saldo alterado depois do pedido barra a aprovação; acordo em vigor impede; carga do credor
 * posterior não sobrescreve (divergência mantida, reconhecida ou aceita); histórico append-only no banco.
 */
@Import(TitleAmountAdjustmentIT.AgreementGuardConfig.class)
class TitleAmountAdjustmentIT extends IntegrationTest {

    static final Set<UUID> TITLES_IN_AGREEMENT = ConcurrentHashMap.newKeySet();

    @TestConfiguration
    static class AgreementGuardConfig {
        @Bean
        @Primary
        TitleAgreementGuard testAgreementGuard() {
            return titleId -> TITLES_IN_AGREEMENT.contains(titleId) ? Optional.of(UUID.randomUUID()) : Optional.empty();
        }
    }

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
    Actor operador;

    @BeforeEach
    void setUp() {
        String slug = "adj-" + UUID.randomUUID().toString().substring(0, 8);
        tenant = tenantExecution.callInTenant(Ids.newId(), "test.setup", () -> tenantAdministration
                .createTenant(new CreateTenantCommand(slug, "Assessoria Ltda", "Assessoria", "11.444.777/0001-61")).id());
        creditor = system(() -> creditors.create(new CreditorCommand("Banco A S.A.", "Banco A", "11.222.333/0001-81", null,
                null)).id());
        portfolio = system(() -> {
            UUID contract = contracts.create(new CreateContractCommand(creditor, "CT-ADJ",
                    new ContractTermsCommand(LocalDate.of(2026, 1, 1), null, null, 10, null))).id();
            return portfolios.create(new CreatePortfolioCommand(creditor, contract, "ADJ", "Carteira", null,
                    LocalDate.of(2026, 1, 1), null)).id();
        });
        debtor = system(() -> ingestion.upsertDebtor(new DebtorRecord("529.982.247-25", "Devedor Teste",
                LocalDate.of(1985, 3, 15), List.of())).id());
        Set<Permission> both = EnumSet.of(Permission.DEBTOR_VIEW, Permission.TITLE_AMOUNT_ADJUST,
                Permission.TITLE_AMOUNT_APPROVE);
        gestor = new Actor(Actor.ActorType.USER, Ids.newId(), tenant, Ids.newId(), "Gestora", both, CreditorScope.ALL);
        financeiro = new Actor(Actor.ActorType.USER, Ids.newId(), tenant, Ids.newId(), "Financeiro", both,
                CreditorScope.ALL);
        operador = new Actor(Actor.ActorType.USER, Ids.newId(), tenant, Ids.newId(), "Operador",
                EnumSet.of(Permission.DEBTOR_VIEW, Permission.CASE_WORK), CreditorScope.ALL);
    }

    private <T> T system(Supplier<T> action) {
        return tenantExecution.callInTenant(tenant, "test", action);
    }

    private <T> T as(Actor actor, Supplier<T> action) {
        return tenantExecution.callAs(actor, action);
    }

    private UUID title(String externalId, String principal) {
        UpsertResult r = system(() -> ingestion.upsertTitle(new TitleRecord(creditor, portfolio, debtor, externalId, null,
                1, LocalDate.of(2026, 3, 10), new BigDecimal(principal), null, null), Ids.newId()));
        return r.id();
    }

    private UpsertResult reload(String externalId, String principal) {
        return system(() -> ingestion.upsertTitle(new TitleRecord(creditor, portfolio, debtor, externalId, null, 1,
                LocalDate.of(2026, 3, 10), new BigDecimal(principal), null, null), Ids.newId()));
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
    void reductionWithinTheLimitIsAppliedAtOnceAndRecordedOnTheTitle() {
        UUID t = title("T-1", "1000.00");
        AdjustmentView applied = as(gestor, () -> adjustments.request(t, principalTo("850.00"), null));
        assertThat(applied.status()).isEqualTo("APPLIED");
        assertThat(applied.approvalMode()).isEqualTo("WITHIN_LIMIT");
        assertThat(applied.previousPrincipal()).isEqualByComparingTo("1000.00");
        assertThat(applied.newPrincipal()).isEqualByComparingTo("850.00");
        assertThat(applied.requestedByName()).isEqualTo("Gestora");
        assertThat(applied.lines()).singleElement().satisfies(l -> assertThat(l.delta()).isEqualByComparingTo("-150.00"));
        assertThat(system(() -> titles.get(t)).openBalance()).isEqualByComparingTo("850.00");
        assertThat(as(operador, () -> adjustments.history(t)).adjustments()).hasSize(1);
        assertThat(count("select count(*) from debt.title_ledger_entry where title_id = ? and entry_type = 'ADJUSTMENT' "
                + "and reference_type = 'amount_adjustment'", t)).isEqualTo(1);
        assertThat(count("select count(*) from shared.business_event where event_type = 'title.amount_adjusted' "
                + "and attributes ->> 'approvalMode' = 'WITHIN_LIMIT'", null)).isEqualTo(1);
        assertThat(count("select count(*) from shared.audit_event where action = 'title_amount.adjusted' and entity_id = ?",
                t)).isEqualTo(1);
        // operador não corrige
        assertThatThrownBy(() -> as(operador, () -> adjustments.request(t, principalTo("800.00"), null)))
                .isInstanceOf(ScopeAccessDeniedException.class);
        // histórico append-only no banco
        assertThatThrownBy(() -> system(() -> jdbc.update("update debt.title_amount_adjustment set reason_code = 'OTHER'"
                + " where id = ?", applied.id()))).rootCause().hasMessageContaining("já decidido");
    }

    @Test
    void aboveTheLimitNeedsEvidenceAndAnotherPersonToApprove() {
        UUID t = title("T-2", "2000.00");
        assertThatThrownBy(() -> as(gestor, () -> adjustments.request(t, principalTo("1000.00"), null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("evidência");
        AdjustmentView pending = as(gestor, () -> adjustments.request(t, principalTo("1000.00"), pdf()));
        assertThat(pending.status()).isEqualTo("PENDING_APPROVAL");
        assertThat(pending.evidence().contentType()).isEqualTo("application/pdf");
        assertThat(system(() -> titles.get(t)).openBalance()).isEqualByComparingTo("2000.00");
        // um pedido pendente por título
        assertThatThrownBy(() -> as(financeiro, () -> adjustments.request(t, principalTo("1900.00"), null)))
                .isInstanceOf(ConflictException.class);
        // quem pediu não aprova nem vê o próprio pedido na fila
        assertThatThrownBy(() -> as(gestor, () -> adjustments.approve(pending.id(), null)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("Quem pediu");
        assertThat(as(gestor, () -> adjustments.pendingApprovals(PageQuery.of(0, 20))).content()).isEmpty();
        assertThat(as(financeiro, () -> adjustments.pendingApprovals(PageQuery.of(0, 20))).content())
                .extracting(AdjustmentView::id).containsExactly(pending.id());

        AdjustmentView approved = as(financeiro, () -> adjustments.approve(pending.id(), "Conferido com o contrato"));
        assertThat(approved.status()).isEqualTo("APPLIED");
        assertThat(approved.decidedByName()).isEqualTo("Financeiro");
        assertThat(system(() -> titles.get(t)).principalAmount()).isEqualByComparingTo("1000.00");
    }

    @Test
    void increasesAlwaysNeedApprovalAndApprovalChecksTheBalancesAreUnchanged() {
        UUID t = title("T-3", "500.00");
        AdjustmentView increase = as(gestor, () -> adjustments.request(t, new AdjustmentCommand(
                Map.of("FINE", new BigDecimal("20.00")), "CONTRACT_TERMS", "Multa contratual não carregada", null), pdf()));
        assertThat(increase.status()).isEqualTo("PENDING_APPROVAL");
        assertThat(increase.delta()).isEqualByComparingTo("20.00");
        // pagamento entra antes da aprovação: a base mudou
        system(() -> ledgerPort.applyPayment(new PaymentApplication(t, Ids.newId(),
                ComponentAmounts.principalOnly(new BigDecimal("100.00")), Instant.now())));
        AdjustmentView pendingOnPrincipal = increase;
        assertThat(pendingOnPrincipal.lines()).singleElement().satisfies(l -> assertThat(l.component()).isEqualTo("FINE"));
        // a multa não mudou: aprova
        assertThat(as(financeiro, () -> adjustments.approve(increase.id(), null)).status()).isEqualTo("APPLIED");
        assertThat(system(() -> titles.get(t)).openBalance()).isEqualByComparingTo("420.00");

        AdjustmentView reduce = as(gestor, () -> adjustments.request(t, principalTo("100.00"), pdf()));
        system(() -> ledgerPort.applyPayment(new PaymentApplication(t, Ids.newId(),
                ComponentAmounts.principalOnly(new BigDecimal("50.00")), Instant.now())));
        assertThatThrownBy(() -> as(financeiro, () -> adjustments.approve(reduce.id(), null)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("saldo do título mudou");
        assertThat(as(financeiro, () -> adjustments.reject(reduce.id(), "Base desatualizada")).status())
                .isEqualTo("REJECTED");
    }

    @Test
    void titleInAgreementOrOutOfCollectionCannotBeCorrected() {
        UUID t = title("T-4", "700.00");
        TITLES_IN_AGREEMENT.add(t);
        try {
            assertThat(as(gestor, () -> adjustments.context(t)).blockers()).contains("AGREEMENT");
            assertThatThrownBy(() -> as(gestor, () -> adjustments.request(t, principalTo("650.00"), null)))
                    .isInstanceOf(ConflictException.class).hasMessageContaining("acordo em vigor");
        } finally {
            TITLES_IN_AGREEMENT.remove(t);
        }
        // zerar o saldo é cancelamento, não correção
        assertThatThrownBy(() -> as(gestor, () -> adjustments.request(t, principalTo("0.00"), null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("cancele o título");
    }

    @Test
    void laterCreditorLoadDoesNotOverwriteTheCorrection() {
        UUID t = title("T-5", "1000.00");
        as(gestor, () -> adjustments.request(t, principalTo("900.00"), null));

        // carga com o valor antigo: mantém a correção e abre a divergência (aviso da linha)
        UpsertResult first = reload("T-5", "1000.00");
        assertThat(first.warnings()).contains("title.principal_divergence");
        assertThat(system(() -> titles.get(t)).principalAmount()).isEqualByComparingTo("900.00");
        List<DivergenceView> open = as(gestor, () -> adjustments.openDivergences(PageQuery.of(0, 20))).content();
        assertThat(open).singleElement().satisfies(d -> {
            assertThat(d.reportedPrincipal()).isEqualByComparingTo("1000.00");
            assertThat(d.currentPrincipal()).isEqualByComparingTo("900.00");
        });
        // repetida: só conta a ocorrência
        assertThat(reload("T-5", "1000.00").warnings()).doesNotContain("title.principal_divergence");
        assertThat(as(gestor, () -> adjustments.openDivergences(PageQuery.of(0, 20))).content().getFirst().occurrences())
                .isEqualTo(2);

        // mantém a correção: o valor do credor fica reconhecido e não reabre
        as(gestor, () -> adjustments.resolveDivergence(open.getFirst().id(), "KEEP_MANUAL", "Credor confirmou por e-mail"));
        reload("T-5", "1000.00");
        assertThat(as(gestor, () -> adjustments.openDivergences(PageQuery.of(0, 20))).content()).isEmpty();

        // valor novo do credor: nova divergência; aceita o valor do credor → lançado no razão
        reload("T-5", "950.00");
        DivergenceView second = as(gestor, () -> adjustments.openDivergences(PageQuery.of(0, 20))).content().getFirst();
        as(gestor, () -> adjustments.resolveDivergence(second.id(), "ACCEPT_CREDITOR", "Credor recalculou"));
        assertThat(system(() -> titles.get(t)).principalAmount()).isEqualByComparingTo("950.00");
        // sem correção manual, a carga volta a valer normalmente
        reload("T-5", "940.00");
        assertThat(system(() -> titles.get(t)).principalAmount()).isEqualByComparingTo("940.00");
    }

    @Test
    void creditorConvergingToTheCorrectionClosesTheDivergence() {
        UUID t = title("T-6", "300.00");
        as(gestor, () -> adjustments.request(t, principalTo("280.00"), null));
        reload("T-6", "300.00");
        reload("T-6", "280.00");
        assertThat(as(gestor, () -> adjustments.history(t)).divergences()).singleElement()
                .satisfies(d -> assertThat(d.status()).isEqualTo("RESOLVED_BY_CREDITOR"));
        assertThat(as(gestor, () -> adjustments.context(t)).manualPrincipal()).isFalse();
    }

    @Test
    void thresholdIsConfigurableAndOnlyApproversChangeIt() {
        UUID t = title("T-7", "400.00");
        as(financeiro, () -> adjustments.updateSettings(new BigDecimal("0.00")));
        assertThat(as(gestor, () -> adjustments.request(t, principalTo("399.00"), pdf())).status())
                .isEqualTo("PENDING_APPROVAL");
        Actor adjustOnly = new Actor(Actor.ActorType.USER, Ids.newId(), tenant, Ids.newId(), "Ajusta",
                EnumSet.of(Permission.TITLE_AMOUNT_ADJUST), CreditorScope.ALL);
        assertThatThrownBy(() -> as(adjustOnly, () -> adjustments.updateSettings(new BigDecimal("500.00"))))
                .isInstanceOf(ScopeAccessDeniedException.class);
    }

    @Test
    void movingValueBetweenComponentsNeedsASecondPersonEvenWithANegativeNet() {
        UUID t = title("T-8", "10000.00");
        // principal 10.000 → 1.000 e honorários 0 → 8.999,99: líquido −0,01, mas sobe a receita da assessoria
        AdjustmentCommand swap = new AdjustmentCommand(Map.of("PRINCIPAL", new BigDecimal("1000.00"),
                "HONORARIUM", new BigDecimal("8999.99")), "CALCULATION_ERROR", "Reclassificação de componentes", null);
        assertThatThrownBy(() -> as(gestor, () -> adjustments.request(t, swap, null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("evidência");
        AdjustmentView pending = as(gestor, () -> adjustments.request(t, swap, pdf()));
        assertThat(pending.status()).isEqualTo("PENDING_APPROVAL");
        assertThat(pending.approvalMode()).isEqualTo("DUAL_CONTROL");
        assertThat(pending.delta()).isEqualByComparingTo("-0.01");
        assertThat(system(() -> titles.get(t)).openBalance()).isEqualByComparingTo("10000.00");
    }

    @Test
    void raisingTheThresholdOnlyCountsAfterAnotherPersonConfirms() {
        UUID t = title("T-9", "10000.00");
        SettingsView proposed = as(gestor, () -> adjustments.updateSettings(new BigDecimal("5000.00")));
        assertThat(proposed.approvalThreshold()).isEqualByComparingTo("200.00");
        assertThat(proposed.proposedThreshold()).isEqualByComparingTo("5000.00");
        assertThat(proposed.proposedBy()).isEqualTo(gestor.id());
        assertThat(proposed.proposedByName()).isEqualTo("Gestora");

        // enquanto não confirmado, a própria redução grande ainda exige segunda pessoa
        assertThatThrownBy(() -> as(gestor, () -> adjustments.request(t, principalTo("6000.00"), null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("evidência");
        // quem propôs não confirma (nem vê a pendência); outra pessoa vê
        assertThatThrownBy(() -> as(gestor, () -> adjustments.confirmSettings(new BigDecimal("5000.00"))))
                .isInstanceOf(ConflictException.class).hasMessageContaining("Quem propôs");
        assertThat(thresholdPending(gestor)).isZero();
        assertThat(thresholdPending(financeiro)).isEqualTo(1);
        // escopo restrito não confirma
        Actor restricted = new Actor(Actor.ActorType.USER, Ids.newId(), tenant, Ids.newId(), "Restrita",
                EnumSet.of(Permission.TITLE_AMOUNT_APPROVE), CreditorScope.of(List.of(creditor)));
        assertThatThrownBy(() -> as(restricted, () -> adjustments.confirmSettings(new BigDecimal("5000.00"))))
                .isInstanceOf(ScopeAccessDeniedException.class);

        // a proposta mudou depois de a pessoa abrir a tela: confirmar o valor antigo é recusado
        assertThatThrownBy(() -> as(financeiro, () -> adjustments.confirmSettings(new BigDecimal("500.00"))))
                .isInstanceOf(ConflictException.class).hasMessageContaining("mudou");
        SettingsView confirmed = as(financeiro, () -> adjustments.confirmSettings(new BigDecimal("5000.00")));
        assertThat(confirmed.approvalThreshold()).isEqualByComparingTo("5000.00");
        assertThat(confirmed.proposedThreshold()).isNull();
        assertThat(thresholdPending(financeiro)).isZero();
        assertThat(as(gestor, () -> adjustments.request(t, principalTo("6000.00"), null)).status()).isEqualTo("APPLIED");
        assertThatThrownBy(() -> as(financeiro, () -> adjustments.confirmSettings(new BigDecimal("5000.00"))))
                .isInstanceOf(ConflictException.class).hasMessageContaining("Não há aumento");

        // reduzir vale na hora e descarta a proposta em aberto
        as(gestor, () -> adjustments.updateSettings(new BigDecimal("90000.00")));
        SettingsView reduced = as(gestor, () -> adjustments.updateSettings(new BigDecimal("100.00")));
        assertThat(reduced.approvalThreshold()).isEqualByComparingTo("100.00");
        assertThat(reduced.proposedThreshold()).isNull();

        assertThat(count("select count(*) from shared.audit_event where action = 'title_amount.settings_proposed'", null))
                .isEqualTo(2);
        assertThat(count("select count(*) from shared.audit_event where action = 'title_amount.settings_confirmed'", null))
                .isEqualTo(1);
        assertThat(count("select count(*) from shared.audit_event where action = 'title_amount.settings_updated'", null))
                .isEqualTo(1);
        // coerência da proposta no banco: aumento sem quem propôs é recusado
        assertThatThrownBy(() -> system(() -> jdbc.update("update debt.amount_adjustment_settings"
                + " set proposed_threshold = 500.00")))
                .rootCause().hasMessageContaining("ck_amount_adjustment_proposal");
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
