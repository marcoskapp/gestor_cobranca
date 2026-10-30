package br.com.gestorcobranca.sandbox;

import br.com.gestorcobranca.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Decisão 643: com o perfil {@code sandbox}, a interface do Swagger e as especificações {@code /v3/api-docs} de todos
 * os grupos abrem sem login; a API em si continua exigindo autenticação. O contraponto (protegido fora do sandbox)
 * está em {@link SandboxApiDocsProtectedIT}. O perfil test vem depois e sobrepõe banco, chaves e diretórios locais.
 */
@ActiveProfiles({"sandbox", "test"})
@TestPropertySource(properties = {
        "app.environment=sandbox",
        "app.storage.local-root=./target/test-storage-sandbox",
        "spring.mail.host=127.0.0.1",
        "spring.mail.port=9"})
class SandboxApiDocsOpenIT extends IntegrationTest {

    @Autowired
    WebApplicationContext context;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void apiDocsOfAllGroupsAndSwaggerUiOpenWithoutLogin() throws Exception {
        MockMvc mvc = mvc();
        mvc.perform(get("/v3/api-docs/swagger-config")).andExpect(status().isOk())
                .andExpect(content().string(containsString("public-v1")));
        mvc.perform(get("/v3/api-docs/interna")).andExpect(status().isOk())
                .andExpect(content().string(containsString("\"/api/v1/")));
        mvc.perform(get("/v3/api-docs/public-v1")).andExpect(status().isOk())
                .andExpect(content().string(containsString("\"/public-api/v1/")));
        mvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        mvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
    }

    @Test
    void onlyTheDocumentationIsOpenedNotTheApi() throws Exception {
        MockMvc mvc = mvc();
        mvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
        mvc.perform(post("/v3/api-docs/interna")).andExpect(status().is4xxClientError());
    }
}
