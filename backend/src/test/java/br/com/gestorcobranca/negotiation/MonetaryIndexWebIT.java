package br.com.gestorcobranca.negotiation;

import br.com.gestorcobranca.identity.domain.model.DefaultRoles;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.ActorAuthentication;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Set;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Contrato HTTP de {@code YearMonth} nos índices de correção: a competência trafega como
 * {@code "yyyy-MM"} na entrada e na saída (o formato usado pelo frontend, {@code <input type="month">}).
 */
class MonetaryIndexWebIT extends NegotiationTestSupport {

    @Autowired
    WebApplicationContext webApplicationContext;

    MockMvc mvc;
    ActorAuthentication managerAuth;

    @BeforeEach
    void setUp() {
        provisionTenant("ixw");
        mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        Set<Permission> permissions = DefaultRoles.forTenant().stream().filter(d -> d.code().equals(DefaultRoles.GESTOR))
                .findFirst().orElseThrow().permissions();
        managerAuth = ActorAuthentication.of(new Actor(Actor.ActorType.USER, manager, tenant, Ids.newId(),
                DefaultRoles.GESTOR, permissions, CreditorScope.ALL));
    }

    @Test
    void competenceIsYearMonthIsoInRequestsAndResponses() throws Exception {
        mvc.perform(post("/api/v1/negotiation/indexes").with(authentication(managerAuth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"IPCA_W\",\"name\":\"IPCA (teste web)\",\"source\":\"IBGE\"}"))
                .andExpect(status().isCreated());

        mvc.perform(put("/api/v1/negotiation/indexes/IPCA_W/rates").with(authentication(managerAuth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rates\":[{\"competence\":\"2026-08\",\"ratePercent\":0.25},"
                                + "{\"competence\":\"2026-09\",\"ratePercent\":0.48}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastCompetence").value("2026-09"))
                .andExpect(jsonPath("$.rates[?(@.competence == '2026-08')].ratePercent").value(0.25));

        mvc.perform(get("/api/v1/negotiation/indexes/IPCA_W").with(authentication(managerAuth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lastCompetence").value("2026-09"))
                .andExpect(jsonPath("$.rates[*].competence").value(org.hamcrest.Matchers.containsInAnyOrder("2026-08",
                        "2026-09")));

        // data completa ou mês sem zero à esquerda não são competências válidas
        for (String invalid : new String[]{"2026-09-01", "2026-9", "09/2026"}) {
            mvc.perform(put("/api/v1/negotiation/indexes/IPCA_W/rates").with(authentication(managerAuth))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"rates\":[{\"competence\":\"" + invalid + "\",\"ratePercent\":0.1}]}"))
                    .andExpect(status().isBadRequest());
        }
    }
}
