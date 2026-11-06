package br.com.gestorcobranca.helpdesk;

import br.com.gestorcobranca.helpdesk.application.port.out.KnowledgeBase;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.ActorAuthentication;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import br.com.gestorcobranca.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Parâmetros do manual com a aplicação inteira: cada módulo dono publica os seus ({@code ManualParameterSource}), o
 * agregador do helpdesk os expõe a todo usuário interno — inclusive sem permissão nenhuma, como o manual — e nega o
 * devedor do portal e quem não entrou; a base do chat sobe com os marcadores resolvidos pelos mesmos valores.
 */
class ManualParametersIT extends IntegrationTest {

    private static final String URL = "/api/v1/helpdesk/manual-parameters";

    @Autowired
    WebApplicationContext webApplicationContext;

    @Autowired
    KnowledgeBase knowledge;

    private static ActorAuthentication actor(Actor.ActorType type, Set<Permission> permissions) {
        return ActorAuthentication.of(new Actor(type, Ids.newId(), Ids.newId(), Ids.newId(), "OPERADOR", permissions,
                CreditorScope.of(Set.of(Ids.newId()))));
    }

    @Test
    void everyInternalUserReadsTheValuesTheModulesApply() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();

        String body = mvc.perform(get(URL).with(authentication(actor(Actor.ActorType.USER, Set.of()))))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> raw = JsonPath.read(body, "$.values");
        Map<String, BigDecimal> values = new TreeMap<>();
        raw.forEach((k, v) -> values.put(k, new BigDecimal(v.toString())));
        assertThat(values.keySet()).containsExactlyInAnyOrderElementsOf(ManualParameterDefaults.DEFAULTS.keySet());
        ManualParameterDefaults.DEFAULTS.forEach((k, v) -> assertThat(values.get(k)).as(k).isEqualByComparingTo(v));
        // só números de configuração: nada da assessoria nem de pessoa
        assertThat(body).doesNotContain("tenant").doesNotContain("creditor");

        // devedor do portal e integração não leem; sem sessão, 401
        mvc.perform(get(URL).with(authentication(actor(Actor.ActorType.DEBTOR, Set.of()))))
                .andExpect(status().isForbidden());
        mvc.perform(get(URL).with(authentication(actor(Actor.ActorType.INTEGRATION, Set.of()))))
                .andExpect(status().isForbidden());
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void theSupportChatKnowledgeBaseIsResolvedWithTheSameValues() {
        assertThat(knowledge.screen("debt.title-amounts").orElseThrow().purpose())
                .anySatisfy(p -> assertThat(p).contains("nos últimos 30 dias").contains("vence em 15 dias"));
        assertThat(knowledge.screens()).allSatisfy(s -> assertThat(String.join(" ", s.purpose()) + s.objective())
                .doesNotContain("{{"));
    }
}
