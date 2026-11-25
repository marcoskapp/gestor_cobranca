package br.com.gestorcobranca.identity;

import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.ActorAuthentication;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Set;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fiação HTTP da fronteira do portal do credor (ADR 0011): cada ator só na sua API — a ordem dos matchers e a lista de
 * permissão da API interna, não só a regra isolada.
 */
class CreditorApiBoundaryIT extends IntegrationTest {

    @Autowired
    WebApplicationContext context;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void cadaAtorSoNaSuaApi() throws Exception {
        MockMvc mvc = mvc();
        UUID tenant = Ids.newId();
        ActorAuthentication creditor = ActorAuthentication.of(Actor.creditor(tenant, Ids.newId(), Ids.newId(), Ids.newId(),
                Set.of(Permission.CREDITOR_PORTAL_OVERVIEW)));
        ActorAuthentication user = ActorAuthentication.of(new Actor(Actor.ActorType.USER, Ids.newId(), tenant, Ids.newId(),
                "u", Set.of(Permission.CREDITOR_VIEW), CreditorScope.ALL));
        ActorAuthentication debtor = ActorAuthentication.of(new Actor(Actor.ActorType.DEBTOR, Ids.newId(), tenant,
                Ids.newId(), "d", Set.of(), CreditorScope.of(Set.of(Ids.newId()))));

        // a pessoa do credor nunca entra na API interna nem na documentação dela
        mvc.perform(get("/api/v1/creditors").with(authentication(creditor))).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/tenant-settings").with(authentication(creditor))).andExpect(status().isForbidden());
        mvc.perform(get("/v3/api-docs").with(authentication(creditor))).andExpect(status().isForbidden());
        mvc.perform(get("/v3/api-docs.yaml").with(authentication(creditor))).andExpect(status().isForbidden());
        mvc.perform(get("/api/portal/v1/me/session").with(authentication(creditor))).andExpect(status().isForbidden());
        // usuário interno e devedor nunca entram no portal do credor
        mvc.perform(get("/api/creditor/v1/overview").with(authentication(user))).andExpect(status().isForbidden());
        mvc.perform(get("/api/creditor/v1/overview").with(authentication(debtor))).andExpect(status().isForbidden());
        mvc.perform(get("/api/creditor/v1/statements").with(authentication(user))).andExpect(status().isForbidden());
        mvc.perform(get("/api/creditor/v1/statements/" + Ids.newId()).with(authentication(debtor)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/creditor/v1/disputes").with(authentication(user))).andExpect(status().isForbidden());
        mvc.perform(get("/api/creditor/v1/disputes/" + Ids.newId()).with(authentication(debtor)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/creditor/v1/disputes")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/creditor/v1/payments").with(authentication(user))).andExpect(status().isForbidden());
        mvc.perform(get("/api/creditor/v1/payments/titles").param("number", "T-1").with(authentication(debtor)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/creditor/v1/payments")).andExpect(status().isUnauthorized());
        // Idempotency-Key fora do formato ou ausente: 400 da própria chave (o detail diz o motivo), antes do comprovante e do
        // código do autenticador (SEG-20261125-I03). O informe é válido para o 400 não vir do @Valid (validation.failed).
        ActorAuthentication reporter = ActorAuthentication.of(Actor.creditor(tenant, Ids.newId(), Ids.newId(), Ids.newId(),
                Set.of(Permission.CREDITOR_PORTAL_OVERVIEW, Permission.CREDITOR_PAYMENT_REPORT)));
        org.springframework.mock.web.MockMultipartFile report = new org.springframework.mock.web.MockMultipartFile("report", "",
                "application/json", """
                {"titleNumbers":["T-1"],"amount":10.00,"paidOn":"2026-11-20","method":"PIX","code":"123456"}
                """.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        org.springframework.mock.web.MockMultipartFile evidence = new org.springframework.mock.web.MockMultipartFile(
                "evidence", "c.pdf", "application/pdf", "%PDF-1.4".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        for (String key : new String[]{null, "x", "chave com espaço", "", "a".repeat(81)}) {
            var request = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/creditor/v1/payments")
                    .file(report).file(evidence)
                    // o CreditorRequestSizeFilter responde 411 sem Content-Length
                    .with(r -> { r.setContent(new byte[64]); return r; })
                    .with(authentication(reporter));
            if (key != null) {
                request.header("Idempotency-Key", key);
            }
            mvc.perform(request).andExpect(status().isBadRequest())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.detail")
                            .value(org.hamcrest.Matchers.startsWith("Idempotency-Key obrigatória")));
        }
        // anônimo: 401
        mvc.perform(get("/api/creditor/v1/overview")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v3/api-docs.yaml")).andExpect(status().isUnauthorized());
    }
}
