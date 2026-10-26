package br.com.gestorcobranca.payment;

import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.ExternalPaymentCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.PaymentView;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.TargetInput;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.ActorAuthentication;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "Minhas pendências" ({@code GET /api/v1/me/pending-work}) contra PostgreSQL real, sobre o cenário do
 * módulo payment (credores A e B, devedor, financeiros): a fonte de pagamentos aplica a segregação
 * (quem informou não vê a própria aprovação) e o escopo por credor; a de prestação de contas exclui
 * quem calculou o demonstrativo; um usuário com todas as permissões executa todas as fontes reais sem
 * indisponibilidade (consultas válidas sob RLS); a resposta não carrega dado pessoal; o log de negócio
 * registra uma observação por usuário, tipo e dia.
 */
class PendingWorkApiIT extends PaymentTestSupport {

    private static final String URL = "/api/v1/me/pending-work";

    @Autowired
    WebApplicationContext webApplicationContext;

    private ActorAuthentication auth(UUID userId, String role, CreditorScope scope) {
        Set<Permission> permissions = DefaultRoles.forTenant().stream().filter(d -> d.code().equals(role)).findFirst()
                .orElseThrow().permissions();
        return ActorAuthentication.of(new Actor(Actor.ActorType.USER, userId, tenant, Ids.newId(), role, permissions,
                scope));
    }

    private String fetch(MockMvc mvc, ActorAuthentication auth) throws Exception {
        return mvc.perform(get(URL).with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
    }

    private static List<Map<String, Object>> items(String json, String type) {
        return JsonPath.read(json, "$.items[?(@.type == '" + type + "')]");
    }

    @Test
    void segregationScopeAllSourcesAndDailyObservation() throws Exception {
        provision("pending");
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();

        // pagamento externo acima do limite informado pelo financeiro 1 (credor B): aguarda segunda pessoa
        Instant paidAt = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        PaymentView informed = asFinance1(() -> payments.registerExternal(new ExternalPaymentCommand(creditorB, debtor,
                new BigDecimal("900.00"), paidAt, "TRANSFER", "AGENCY_ACCOUNT", "DOC-P1", "Depósito",
                new TargetInput("TITLES", null, List.of(titleB), null)), pdf(), null));
        assertThat(informed.status()).isEqualTo("INFORMED");

        // quem informou não vê a aprovação (não pode aprová-la)
        String own = fetch(mvc, auth(finance1, DefaultRoles.FINANCEIRO, CreditorScope.ALL));
        assertThat(items(own, "payment.external_review")).isEmpty();
        assertThat(JsonPath.<List<Object>>read(own, "$.unavailable")).isEmpty();

        // a segunda pessoa vê, com destino na fila de aprovação e o item mais antigo
        String other = fetch(mvc, auth(finance2, DefaultRoles.FINANCEIRO, CreditorScope.ALL));
        assertThat(items(other, "payment.external_review")).singleElement().satisfies(i -> {
            assertThat(i).containsEntry("count", 1).containsEntry("severity", "NORMAL")
                    .containsEntry("route", "/pagamentos/aprovacoes").containsEntry("source", "payment")
                    .containsEntry("area", "Pagamentos");
            assertThat(i.get("oldestAt")).isNotNull();
        });
        // sem dado pessoal na resposta (nome e documento do devedor, valor)
        assertThat(other).doesNotContain("Maria").doesNotContain("529.982").doesNotContain("52998224725")
                .doesNotContain("900.00");

        // escopo por credor: com acesso só ao credor A, o pagamento do credor B não aparece
        String scoped = fetch(mvc, auth(finance2, DefaultRoles.FINANCEIRO, CreditorScope.of(Set.of(creditorA))));
        assertThat(items(scoped, "payment.external_review")).isEmpty();

        // prestação de contas: quem calculou o demonstrativo revisado não vê a aprovação dele
        UUID statement = Ids.newId();
        system(() -> jdbcTemplate.update("""
                insert into settlement.statement (id, tenant_id, version, created_at, updated_at, creditor_id,
                    sequence_number, period_start, period_end, status, received_agency_amount,
                    received_creditor_amount, reversed_amount, recovered_amount, reconciled_amount, agency_honorarium,
                    creditor_honorarium, commission_amount, fixed_fee_amount, expenses_amount, adjustments_amount,
                    net_transfer_amount, transferred_amount, negotiated_amount, forecast_commission,
                    pending_reconciliation_amount, pending_rules_amount, open_divergences, receipts_count,
                    calculation_schema, calculated_at, calculated_by, reviewed_at, reviewed_by)
                values (?, ?, 0, now(), now(), ?, 1, date '2026-09-01', date '2026-09-30', 'REVIEWED',
                    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, ?, ?, ?, ?)
                """, statement, tenant, creditorA, Timestamp.from(Instant.now().minusSeconds(7200)), manager,
                Timestamp.from(Instant.now().minusSeconds(3600)), finance1));
        String calculator = fetch(mvc, auth(manager, DefaultRoles.GESTOR, CreditorScope.ALL));
        assertThat(items(calculator, "settlement.statement_approval")).isEmpty();
        String approver = fetch(mvc, auth(admin, DefaultRoles.GESTOR, CreditorScope.ALL));
        assertThat(items(approver, "settlement.statement_approval")).singleElement()
                .satisfies(i -> assertThat(i).containsEntry("count", 1)
                        .containsEntry("route", "/prestacao-de-contas/demonstrativos/" + statement));

        // todas as fontes reais executam sob RLS para quem tem todas as permissões, sem indisponibilidade
        UUID everyone = Ids.newId();
        ActorAuthentication all = ActorAuthentication.of(new Actor(Actor.ActorType.USER, everyone, tenant, Ids.newId(),
                "TODAS", EnumSet.allOf(Permission.class), CreditorScope.ALL));
        String full = fetch(mvc, all);
        assertThat(JsonPath.<List<Object>>read(full, "$.unavailable")).isEmpty();
        assertThat(items(full, "payment.external_review")).hasSize(1);
        assertThat(items(full, "settlement.statement_approval")).hasSize(1);
        assertThat(JsonPath.<Integer>read(full, "$.total")).isGreaterThanOrEqualTo(2);

        // log de negócio: uma observação por usuário, tipo e dia, mesmo com consultas repetidas
        fetch(mvc, auth(finance2, DefaultRoles.FINANCEIRO, CreditorScope.ALL));
        assertThat(count("select count(*) from pendingwork.daily_observation where user_id = ? and item_type = ?",
                finance2, "payment.external_review")).isEqualTo(1);
        assertThat(count("select count(*) from shared.business_event where event_type = 'pending_work.observed'"
                + " and actor_id = ? and attributes ->> 'itemType' = 'payment.external_review'", finance2)).isEqualTo(1);
    }

    @Test
    void onlyAuthenticatedUsers() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
        ActorAuthentication debtorActor = ActorAuthentication.of(new Actor(Actor.ActorType.DEBTOR, Ids.newId(), Ids.newId(),
                Ids.newId(), "devedor", Set.of(), CreditorScope.NONE));
        mvc.perform(get(URL).with(authentication(debtorActor))).andExpect(status().isForbidden());
    }
}
