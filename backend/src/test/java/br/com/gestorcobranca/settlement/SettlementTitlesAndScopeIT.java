package br.com.gestorcobranca.settlement;

import br.com.gestorcobranca.identity.application.port.in.TenantProvisioning;
import br.com.gestorcobranca.identity.application.port.in.TenantProvisioning.ProvisionTenantCommand;
import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.ExternalPaymentCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.PaymentView;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.TargetInput;
import br.com.gestorcobranca.settlement.application.port.in.CommissionLedgerQueries.ReceiptFilter;
import br.com.gestorcobranca.settlement.application.port.in.CommissionLedgerQueries.ReceiptView;
import br.com.gestorcobranca.settlement.application.port.in.CommissionRuleUseCases.RuleSetCommand;
import br.com.gestorcobranca.settlement.application.port.in.CommissionRuleUseCases.RuleSetView;
import br.com.gestorcobranca.settlement.application.port.in.SettlementStatementUseCases.CreateStatementCommand;
import br.com.gestorcobranca.settlement.application.port.in.SettlementStatementUseCases.ExpenseView;
import br.com.gestorcobranca.settlement.application.port.in.SettlementStatementUseCases.StatementFilter;
import br.com.gestorcobranca.settlement.application.port.in.SettlementStatementUseCases.StatementView;
import br.com.gestorcobranca.settlement.domain.model.rules.CommissionRules;
import br.com.gestorcobranca.settlement.domain.model.rules.CommissionRules.HonorariumPolicy;
import br.com.gestorcobranca.settlement.domain.model.rules.HonorariumOwnership;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.events.legal.LegalExpenseChanged;
import br.com.gestorcobranca.shared.events.payment.PaymentConfirmed;
import br.com.gestorcobranca.shared.events.payment.PaymentReversed;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Títulos avulsos, pagamento direto ao credor, recebimento aguardando regra até a publicação, custas do
 * jurídico no demonstrativo, estorno entregue antes do pagamento, escopo por credor e isolamento entre
 * assessorias (RLS).
 */
class SettlementTitlesAndScopeIT extends SettlementTestSupport {

    @Test
    void titlePaymentsPendingRulesExpensesOutOfOrderReversalScopeAndIsolation() {
        provision("settle-b");

        // pagamento avulso recebido pelo próprio credor, antes de haver regra no contrato B
        Instant paidAt = Instant.now().minus(2, ChronoUnit.DAYS);
        PaymentView direct = asFinance1(() -> payments.registerExternal(new ExternalPaymentCommand(creditorB, debtor,
                new BigDecimal("300.00"), paidAt, "TRANSFER", "CREDITOR_DIRECT", "REL-1", null,
                new TargetInput("TITLES", null, List.of(titleB), null)), pdf(), null));
        assertThat(direct.status()).isEqualTo("CONFIRMED");
        UUID allocation = direct.allocations().getFirst().id();
        awaitTrue("recebimento aguardando regra", () -> count("select count(*) from settlement.receipt "
                + "where allocation_id = ? and rating_status = 'PENDING_RULES' and pending_reason = 'NO_RULES'",
                allocation) == 1);
        assertThat(count("select count(*) from shared.business_event where event_type = "
                + "'settlement.receipt_pending_rules' and tenant_id = ?", tenant)).isEqualTo(1);

        // publicação das regras apura o pendente; honorários do credor, sem exigência de conciliação
        CommissionRules rules = new CommissionRules(new BigDecimal("10"), List.of(), null,
                new HonorariumPolicy(HonorariumOwnership.CREDITOR, null), false);
        RuleSetView draft = asFinance2(() -> ruleSets.createDraft(new RuleSetCommand(contractB, rules, null)));
        asFinance1(() -> ruleSets.publish(draft.id(), LocalDate.of(2026, 1, 1)));
        ReceiptView rated = asFinance1(() -> ledger.receipts(new ReceiptFilter(creditorB, null, null, null, "RATED",
                null), PageQuery.of(0, 10))).content().getFirst();
        assertThat(rated.allocationId()).isEqualTo(allocation);
        assertThat(rated.receivedBy()).isEqualTo("CREDITOR");
        assertThat(rated.titleReference()).isEqualTo("B-1/1");
        assertThat(rated.daysPastDue()).isEqualTo(18);
        assertThat(rated.paymentType()).isEqualTo("LUMP_SUM");
        // 300,00 × 10% = 30,00; o credor recebeu, então deve 30,00 à assessoria
        assertThat(rated.entry().calculation().commission()).isEqualByComparingTo("30.00");
        assertThat(rated.entry().calculation().transferEffect()).isEqualByComparingTo("-30.00");
        // nova versão não pode alcançar recebimento já apurado
        RuleSetView second = asFinance2(() -> ruleSets.createDraft(new RuleSetCommand(contractB, rules, "v2")));
        assertThatThrownBy(() -> asFinance1(() -> ruleSets.publish(second.id(), paidAt.atZone(
                br.com.gestorcobranca.shared.config.TimeConfig.BUSINESS_ZONE).toLocalDate())))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("apurados");

        // custas do jurídico: adiantada pela assessoria (deduzida) e paga pelo credor (informativa)
        UUID agencyExpense = Ids.newId();
        publish(new LegalExpenseChanged(Ids.newId(), tenant, Instant.now(), agencyExpense, Ids.newId(), creditorB,
                "COURT_FEES", new BigDecimal("150.00"), today.minusDays(3), "AGENCY", true, true, "RECORDED", null));
        publish(new LegalExpenseChanged(Ids.newId(), tenant, Instant.now(), Ids.newId(), Ids.newId(), creditorB,
                "REGISTRY_FEES", new BigDecimal("80.00"), today.minusDays(3), "CREDITOR", true, true, "RECORDED", null));
        awaitConsumed("settlement.legal-expense", 2);

        // estorno entregue antes do pagamento da mesma alocação: guardado e aplicado quando o pagamento chega
        UUID lateAllocation = Ids.newId();
        publish(new PaymentReversed(Ids.newId(), tenant, Instant.now().minus(1, ChronoUnit.DAYS), Ids.newId(),
                lateAllocation, debtor, creditorB, portfolioB, null, new BigDecimal("100.00"), "CHARGEBACK"));
        awaitConsumed("settlement.payment-reversed", 1);
        publish(new PaymentConfirmed(Ids.newId(), tenant, Instant.now(), lateAllocation, debtor, creditorB, portfolioB,
                null, new BigDecimal("100.00"), paidAt, "EXTERNAL", "TRANSFER", List.of(new PaymentConfirmed.Allocation(
                titleB, null, new BigDecimal("100.00"), "B-1/1", 18, BigDecimal.ZERO))));
        awaitTrue("pagamento e estorno apurados", () -> count("select count(*) from settlement.receipt "
                + "where allocation_id = ? and rating_status = 'RATED'", lateAllocation) == 2);
        assertThat(decimal("select sum(amount) from settlement.receipt where allocation_id = ?", lateAllocation))
                .isEqualByComparingTo("0.00");

        // demonstrativo do credor B por quem só enxerga o credor B
        StatementView sb = asFinanceB(() -> statements.create(new CreateStatementCommand(creditorB, today.minusDays(10),
                today.minusDays(1))));
        StatementView calculated = asFinanceB(() -> statements.calculate(sb.id()));
        assertThat(calculated.totals().receipts()).isEqualTo(3);
        assertThat(calculated.totals().receivedByCreditor()).isEqualByComparingTo("300.00");
        assertThat(calculated.totals().receivedByAgency()).isEqualByComparingTo("100.00");
        assertThat(calculated.totals().reversed()).isEqualByComparingTo("-100.00");
        assertThat(calculated.totals().recovered()).isEqualByComparingTo("300.00");
        assertThat(calculated.totals().commission()).isEqualByComparingTo("30.00");
        assertThat(calculated.totals().expenses()).isEqualByComparingTo("150.00");
        // −30,00 (comissão sobre o recebido pelo credor) − 150,00 (custas adiantadas) = −180,00
        assertThat(calculated.totals().netTransfer()).isEqualByComparingTo("-180.00");
        List<ExpenseView> expenses = asFinanceB(() -> statements.expenses(sb.id()));
        assertThat(expenses).extracting(ExpenseView::effect).containsExactlyInAnyOrder("DEDUCT", "INFORMATIVE");

        // escopo por credor
        StatementView sa = asFinance1(() -> statements.create(new CreateStatementCommand(creditorA, today.minusDays(10),
                today.minusDays(1))));
        assertThatThrownBy(() -> asFinanceB(() -> statements.get(sa.id()))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> asFinanceB(() -> statements.calculate(sa.id())))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> asFinanceB(() -> statements.create(new CreateStatementCommand(creditorA, null, null))))
                .isInstanceOf(ScopeAccessDeniedException.class);
        assertThat(asFinanceB(() -> statements.search(new StatementFilter(null, null), PageQuery.of(0, 20))).content())
                .extracting(StatementView::creditorId).containsOnly(creditorB);
        assertThat(asFinanceB(() -> ledger.receipts(new ReceiptFilter(null, null, null, null, null, null),
                PageQuery.of(0, 50))).content()).extracting(ReceiptView::creditorId).containsOnly(creditorB);
        assertThatThrownBy(() -> asFinanceB(() -> ruleSets.list(creditorA, null)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(count("select count(*) from shared.access_log where event_type = 'SCOPE_DENIED' and tenant_id = ?",
                tenant)).isGreaterThanOrEqualTo(3);
        // operador não tem permissão de prestação de contas
        assertThatThrownBy(() -> asOperator(() -> statements.get(sb.id())))
                .isInstanceOf(ScopeAccessDeniedException.class);

        // isolamento entre assessorias (RLS): outra assessoria não vê nada do settlement
        String slug = "other-" + UUID.randomUUID().toString().substring(0, 8);
        TenantProvisioning.ProvisionedTenant other = tenantExecution.callInTenant(Ids.newId(), "test.setup",
                () -> provisioning.provision(new ProvisionTenantCommand(slug, "Outra Assessoria Ltda", "Outra",
                        "11.444.777/0001-61", "Admin", "admin@" + slug + ".test")));
        UUID otherTenant = other.tenant().id();
        Long visible = tenantExecution.callInTenant(otherTenant, "test", () -> jdbcTemplate.queryForObject(
                "select count(*) from settlement.receipt", Long.class));
        assertThat(visible).isZero();
        Long statementsVisible = tenantExecution.callInTenant(otherTenant, "test", () -> jdbcTemplate.queryForObject(
                "select count(*) from settlement.statement", Long.class));
        assertThat(statementsVisible).isZero();
        assertThatThrownBy(() -> tenantExecution.callAs(new br.com.gestorcobranca.shared.security.Actor(
                br.com.gestorcobranca.shared.security.Actor.ActorType.USER, other.adminUserId(), otherTenant, Ids.newId(),
                DefaultRoles.ADMIN, DefaultRoles.forTenant().getFirst().permissions(), CreditorScope.ALL),
                () -> statements.get(sb.id()))).isInstanceOf(ResourceNotFoundException.class);
        Long withoutTenant = jdbcTemplate.queryForObject("select count(*) from settlement.receipt", Long.class);
        assertThat(withoutTenant).isZero();
    }
}
