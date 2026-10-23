package br.com.gestorcobranca.payment;

import br.com.gestorcobranca.payment.application.port.in.PaymentInstructionUseCases.CreateInstructionCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.ExternalPaymentCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.PaymentView;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.ReverseCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.TargetInput;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Escopo por credor (404 em leitura, 403 em comandos), permissões e isolamento entre assessorias (RLS). */
class PaymentIsolationIT extends PaymentTestSupport {

    private PaymentView pay(UUID creditorId, UUID title) {
        return asOperator(() -> payments.registerExternal(new ExternalPaymentCommand(creditorId, debtor,
                new BigDecimal("40.00"), Instant.now().minus(1, ChronoUnit.HOURS), "CASH", "CREDITOR_DIRECT", null, null,
                new TargetInput("TITLES", null, List.of(title), null)), pdf(), null));
    }

    @Test
    void creditorScopePermissionsAndRowLevelSecurity() {
        provision("iso");
        PaymentView paymentA = pay(creditorA, title60);
        PaymentView paymentB = pay(creditorB, titleB);

        // operador com escopo só do credor B
        assertThatThrownBy(() -> asOperatorB(() -> payments.get(paymentA.id()))).isInstanceOf(ResourceNotFoundException.class);
        assertThat(asOperatorB(() -> payments.get(paymentB.id())).id()).isEqualTo(paymentB.id());
        assertThat(asOperatorB(() -> payments.search(null, PageQuery.of(0, 20))).content()).extracting(PaymentView::id)
                .containsExactly(paymentB.id());
        assertThatThrownBy(() -> asOperatorB(() -> payments.registerExternal(new ExternalPaymentCommand(creditorA, debtor,
                BigDecimal.TEN, Instant.now(), "CASH", "OTHER", null, null, new TargetInput("TITLES", null,
                List.of(title60), null)), pdf(), null))).isInstanceOf(ScopeAccessDeniedException.class);
        assertThatThrownBy(() -> asOperatorB(() -> instructions.create(new CreateInstructionCommand("PIX_IMMEDIATE", null,
                null, title60, null, null, null, null), null))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> asOperatorB(() -> payments.findByAllocation(paymentA.allocations().getFirst().id())))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(count("select count(*) from shared.access_log where event_type = 'SCOPE_DENIED'")).isPositive();

        // permissões: operador não estorna, não revisa, não configura contas Pix nem concilia
        assertThatThrownBy(() -> asOperator(() -> payments.reverse(paymentA.id(), new ReverseCommand("OTHER", "x"))))
                .isInstanceOf(ScopeAccessDeniedException.class);
        assertThatThrownBy(() -> asOperator(() -> pixAccounts.list())).isInstanceOf(ScopeAccessDeniedException.class);
        assertThatThrownBy(() -> asOperator(() -> reconciliation.imports(PageQuery.of(0, 10))))
                .isInstanceOf(ScopeAccessDeniedException.class);
        assertThatThrownBy(() -> asOperator(() -> payments.reviewQueue(PageQuery.of(0, 10))))
                .isInstanceOf(ScopeAccessDeniedException.class);
        // a conta Pix da assessoria (sem credor) só é visível a quem tem todos os credores
        assertThat(asAdmin(() -> pixAccounts.list())).singleElement().satisfies(a -> {
            assertThat(a.credentialKeys()).contains("clientId", "webhookSecret");
            assertThat(a.toString()).doesNotContain(WEBHOOK_SECRET);
        });
        assertThat(count("select count(*) from payment.pix_account where credentials_encrypted like '%" + WEBHOOK_SECRET
                + "%'")).isZero();

        // RLS: outra assessoria não vê nada do módulo
        UUID firstTenant = tenant;
        provision("iso2");
        assertThat(count("select count(*) from payment.payment")).isZero();
        assertThat(count("select count(*) from payment.payment_allocation")).isZero();
        assertThat(count("select count(*) from payment.payment_allocation_line")).isZero();
        assertThat(tenantExecution.callInTenant(firstTenant, "test", () -> jdbcTemplate.queryForObject(
                "select count(*) from payment.payment", Long.class))).isEqualTo(2L);
        assertThat(tenantExecution.callInTenant(firstTenant, "test", () -> jdbcTemplate.queryForObject(
                "select count(*) from payment.payment_allocation_line", Long.class))).isEqualTo(2L);
        // sem tenant na transação, nenhuma linha
        assertThat(jdbcTemplate.queryForObject("select count(*) from payment.payment", Long.class)).isZero();
        assertThatThrownBy(() -> asOperator(() -> payments.get(paymentA.id()))).isInstanceOf(ResourceNotFoundException.class);
    }
}
