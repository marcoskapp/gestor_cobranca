package br.com.gestorcobranca.payment;

import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.ActorAuthentication;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.Permission;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.EnumSet;
import java.util.Map;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ordenação por coluna das listas (shared.paging.SortableFields) contra PostgreSQL real: cada campo da lista branca
 * de cada endpoint ordena nas duas direções (a propriedade existe na entidade e a consulta roda sob RLS), e um campo
 * fora da lista — mesmo sendo propriedade real da entidade — é recusado com 400 {@code request.invalid_sort}.
 */
class ListSortingIT extends PaymentTestSupport {

    private static final Map<String, String[]> SORTABLE = Map.of(
            "/api/v1/collection/cases", new String[] {"openBalance", "daysOverdue", "nextActionAt", "openedAt"},
            "/api/v1/debtors", new String[] {"name", "createdAt"},
            "/api/v1/titles", new String[] {"dueDate", "daysOverdue", "openBalance"},
            "/api/v1/negotiation/proposals", new String[] {"createdAt", "totalAmount"},
            "/api/v1/negotiation/agreements", new String[] {"formalizedAt", "totalAmount"},
            "/api/v1/payment/payments", new String[] {"paidAt", "amount"},
            "/api/v1/ingestion/batches", new String[] {"createdAt", "rowsRead"});

    @Autowired
    WebApplicationContext webApplicationContext;

    @Test
    void sortsByWhitelistedFieldsAndRejectsOthers() throws Exception {
        provision("sort");
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
        ActorAuthentication all = ActorAuthentication.of(new Actor(Actor.ActorType.USER, Ids.newId(), tenant, Ids.newId(),
                "TODAS", EnumSet.allOf(Permission.class), CreditorScope.ALL));

        for (Map.Entry<String, String[]> endpoint : SORTABLE.entrySet()) {
            for (String field : endpoint.getValue()) {
                for (String direction : new String[] {"asc", "desc"}) {
                    mvc.perform(get(endpoint.getKey()).param("sort", field + "," + direction).with(authentication(all)))
                            .andExpect(status().isOk());
                }
            }
            // propriedade interna (não publicada) e formato malformado: nunca chegam ao banco
            mvc.perform(get(endpoint.getKey()).param("sort", "tenantId,asc").with(authentication(all)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("request.invalid_sort"));
            mvc.perform(get(endpoint.getKey()).param("sort", "name;drop").with(authentication(all)))
                    .andExpect(status().isBadRequest());
        }
    }
}
