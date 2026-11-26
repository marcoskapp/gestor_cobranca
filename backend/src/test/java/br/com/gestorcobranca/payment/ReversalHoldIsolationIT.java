package br.com.gestorcobranca.payment;

import br.com.gestorcobranca.debt.application.port.in.ReversalHoldUseCases.HoldFilter;
import br.com.gestorcobranca.payment.application.port.in.PaymentReversalHoldsUseCases;
import br.com.gestorcobranca.payment.application.port.in.PaymentReversalHoldsUseCases.HeldReversalItem;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.ExternalPaymentCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.PaymentView;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.ReverseCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentUseCases.TargetInput;
import br.com.gestorcobranca.publicapi.domain.model.TokenScope;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.ActorAuthentication;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RS-117 (IDOR) e RS-114/RS-119 (quem lê e libera) da retenção após estorno contra PostgreSQL real: a lista e a
 * contagem seguem o escopo por credor; GET e POST {@code /release} de retenção de outro credor, de outra assessoria ou
 * inexistente dão o mesmo 404 (corpo igual, normalizados o id, o instante e o rastreio); ator do credor, integração e
 * assistente nunca leem nem liberam; nenhum escopo de token nem permissão do portal do credor leva à liberação.
 */
class ReversalHoldIsolationIT extends PaymentTestSupport {

    @Autowired
    PaymentReversalHoldsUseCases reversalHolds;
    @Autowired
    WebApplicationContext webApplicationContext;

    private final Instant paidAt = Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
    }

    private Actor user(Set<Permission> permissions, CreditorScope scope) {
        return new Actor(Actor.ActorType.USER, Ids.newId(), tenant, Ids.newId(), "PERSONALIZADO",
                EnumSet.copyOf(permissions), scope);
    }

    private <T> T as(Actor actor, Supplier<T> action) {
        return tenantExecution.callAs(actor, action);
    }

    private UUID heldPayment(UUID creditorId, UUID titleId) {
        PaymentView paid = asOperator(() -> payments.registerExternal(new ExternalPaymentCommand(creditorId, debtor,
                new BigDecimal("50.00"), paidAt, "PIX", "CREDITOR_DIRECT", null, null,
                new TargetInput("TITLES", null, List.of(titleId), null)), pdf(), null));
        asFinance1(() -> payments.reverse(paid.id(), new ReverseCommand("OTHER", "Credor desfez o informe")));
        return system(() -> jdbcTemplate.queryForObject("select id from debt.reversal_hold where payment_id = ?",
                UUID.class, paid.id()));
    }

    private static String normalized(String body, UUID id) {
        return body.replace(id.toString(), "<id>").replaceAll("\"(timestamp|instant|traceId|requestId)\"\\s*:\\s*\"[^\"]*\"",
                "\"$1\":\"<x>\"");
    }

    @Test
    void sameNotFoundForOtherCreditorOtherTenantAndRandomId() throws Exception {
        provision("retencao-idor-t2");
        UUID otherTenantHold = heldPayment(creditorB, titleB);
        provision("retencao-idor");
        UUID holdA = heldPayment(creditorA, title60);
        UUID holdB = heldPayment(creditorB, titleB);
        HoldFilter period = new HoldFilter(today.minusDays(1), today.plusDays(1), null, null);

        Actor onlyA = user(Set.of(Permission.PAYMENT_VIEW, Permission.PAYMENT_REVIEW, Permission.DEBTOR_VIEW),
                CreditorScope.of(Set.of(creditorA)));
        // lista e contagem com o escopo explícito
        List<HeldReversalItem> rows = as(onlyA, () -> reversalHolds.search(period, PageQuery.of(0, 50))).content();
        assertThat(rows).extracting(r -> r.hold().id()).containsExactly(holdA);
        assertThat(as(onlyA, () -> reversalHolds.search(period, PageQuery.of(0, 50))).totalElements()).isEqualTo(1);
        assertThat(asAdmin(() -> reversalHolds.search(period, PageQuery.of(0, 50))).content())
                .extracting(r -> r.hold().id()).containsExactlyInAnyOrder(holdA, holdB).doesNotContain(otherTenantHold);
        // credor fora do escopo no filtro: mesma recusa das outras listas
        assertThatThrownBy(() -> as(onlyA, () -> reversalHolds.search(new HoldFilter(today, today, creditorB, null),
                PageQuery.of(0, 20)))).isInstanceOf(br.com.gestorcobranca.shared.domain.ResourceNotFoundException.class);

        MockMvc mvc = mvc();
        UUID random = UUID.randomUUID();
        String reference = null;
        for (UUID id : List.of(holdB, otherTenantHold, random)) {
            String getBody = mvc.perform(get("/api/v1/payment/reversal-holds/" + id)
                            .with(authentication(ActorAuthentication.of(onlyA))))
                    .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
            String postBody = mvc.perform(post("/api/v1/payment/reversal-holds/" + id + "/release")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"version\":0,\"reasonCode\":\"OTHER\",\"note\":\"x\",\"confirmedWithCreditor\":true}")
                            .with(authentication(ActorAuthentication.of(onlyA))))
                    .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
            String both = normalized(getBody, id) + "|" + normalized(postBody, id);
            if (reference == null) {
                reference = both;
            } else {
                assertThat(both).isEqualTo(reference);
            }
        }
        assertThat(count("select count(*) from debt.reversal_hold where id = ? and state = 'PENDING'", holdB))
                .isEqualTo(1);

        // /by-payment de pagamento de outro credor ou inexistente: a mesma lista vazia
        UUID paymentB = system(() -> jdbcTemplate.queryForObject("select payment_id from debt.reversal_hold where id = ?",
                UUID.class, holdB));
        for (UUID id : List.of(paymentB, UUID.randomUUID())) {
            mvc.perform(get("/api/v1/payment/reversal-holds/by-payment/" + id)
                            .with(authentication(ActorAuthentication.of(onlyA))))
                    .andExpect(status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json("[]"));
        }
        // selo: devedor só do credor B ou inexistente → o mesmo 404 da lista de títulos (H3-03)
        UUID[] onlyB = debtorWithTitle(creditorB, portfolioB, 55, "Devedor Ficticio B", "ISO-B", "70.00");
        String seal = null;
        for (UUID id : List.of(onlyB[0], UUID.randomUUID())) {
            String body = mvc.perform(get("/api/v1/titles/reversal-holds").param("debtorId", id.toString())
                            .with(authentication(ActorAuthentication.of(onlyA))))
                    .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
            if (seal == null) {
                seal = normalized(body, id);
            } else {
                assertThat(normalized(body, id)).isEqualTo(seal);
            }
        }
        // sem DEBTOR_VIEW nem CASE_VIEW: recusa (403)
        mvc.perform(get("/api/v1/titles/reversal-holds").param("debtorId", debtor.toString())
                        .with(authentication(ActorAuthentication.of(user(Set.of(Permission.PAYMENT_VIEW),
                                CreditorScope.ALL)))))
                .andExpect(status().isForbidden());
    }

    @Test
    void creditorIntegrationAndAssistantNeverReadNorRelease() throws Exception {
        provision("retencao-atores");
        UUID hold = heldPayment(creditorB, titleB);
        MockMvc mvc = mvc();
        Actor creditorPerson = new Actor(Actor.ActorType.CREDITOR, Ids.newId(), tenant, Ids.newId(), "CREDOR",
                EnumSet.copyOf(Permission.CREDITOR_ONLY), CreditorScope.of(List.of(creditorB)));
        Actor integration = new Actor(Actor.ActorType.INTEGRATION, Ids.newId(), tenant, null, "integration:x",
                EnumSet.of(Permission.PAYMENT_VIEW, Permission.PAYMENT_REVIEW), CreditorScope.of(List.of(creditorB)));
        Actor assistant = Actor.assistant(tenant, Ids.newId(), "padrao",
                Set.of(Permission.PAYMENT_VIEW, Permission.PAYMENT_REVIEW), Set.of(creditorB));
        for (Actor actor : List.of(creditorPerson, integration, assistant)) {
            mvc.perform(get("/api/v1/payment/reversal-holds").param("from", today.toString())
                    .param("to", today.toString()).with(authentication(ActorAuthentication.of(actor))))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/v1/payment/reversal-holds/" + hold + "/release")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"version\":0,\"reasonCode\":\"OTHER\",\"note\":\"x\",\"confirmedWithCreditor\":true}")
                    .with(authentication(ActorAuthentication.of(actor)))).andExpect(status().isForbidden());
        }
        assertThatThrownBy(() -> as(integration, () -> reversalHolds.get(hold)))
                .isInstanceOf(ScopeAccessDeniedException.class);
        // nenhum escopo de token da API pública nem permissão do portal do credor dá PAYMENT_REVIEW
        assertThat(Arrays.stream(TokenScope.values()).flatMap(s -> s.permissions().stream()))
                .doesNotContain(Permission.PAYMENT_REVIEW);
        assertThat(Permission.CREDITOR_ONLY).doesNotContain(Permission.PAYMENT_REVIEW);
        // sem PAYMENT_REVIEW (operador): vê a aba, mas não libera
        mvc.perform(post("/api/v1/payment/reversal-holds/" + hold + "/release").contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":0,\"reasonCode\":\"OTHER\",\"note\":\"x\",\"confirmedWithCreditor\":true}")
                .with(authentication(ActorAuthentication.of(user(Set.of(Permission.PAYMENT_VIEW), CreditorScope.ALL)))))
                .andExpect(status().isForbidden());
        assertThat(count("select count(*) from debt.reversal_hold where id = ? and state = 'PENDING'", hold))
                .isEqualTo(1);
    }

    @Test
    void holdTableHasRlsNoDeleteAndImmutableCreationFields() {
        provision("retencao-banco");
        UUID hold = heldPayment(creditorB, titleB);
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update("delete from debt.reversal_hold where id = ?", hold)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update(
                "update debt.reversal_hold set placed_by = ? where id = ?", Ids.newId(), hold)))
                .rootCause().hasMessageContaining("imutáveis");
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update(
                "delete from debt.reversal_hold_item where hold_id = ?", hold)))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        // RLS: outra assessoria não enxerga a linha
        provision("retencao-banco-t2");
        assertThat(count("select count(*) from debt.reversal_hold where id = ?", hold)).isZero();
        assertThat(count("select count(*) from debt.reversal_hold_item where hold_id = ?", hold)).isZero();
    }
}
