package br.com.gestorcobranca.sandbox;

import br.com.gestorcobranca.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Decisão 643, contraponto de {@link SandboxApiDocsOpenIT}: fora do perfil {@code sandbox} a documentação exige login. */
class SandboxApiDocsProtectedIT extends IntegrationTest {

    @Autowired
    WebApplicationContext context;

    @Test
    void apiDocsAndSwaggerUiRequireAuthentication() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        mvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v3/api-docs/interna")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v3/api-docs/public-v1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isUnauthorized());
        // atalho fora da regra autenticada (cai nos estáticos da SPA); sem a interface habilitada, não serve nada
        mvc.perform(get("/swagger-ui.html")).andExpect(status().isNotFound());
    }
}
