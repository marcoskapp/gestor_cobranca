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
        // anônimo: 401
        mvc.perform(get("/api/creditor/v1/overview")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v3/api-docs.yaml")).andExpect(status().isUnauthorized());
    }
}
