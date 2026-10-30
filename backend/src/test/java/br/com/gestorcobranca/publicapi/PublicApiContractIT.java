package br.com.gestorcobranca.publicapi;

import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.ActorAuthentication;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.core.type.TypeReference;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Controle de mudança do contrato da API pública: o contrato gerado pelo código (grupo {@code public-v1}) precisa ser
 * idêntico ao arquivo versionado {@code docs/api/public-api/v1/openapi.json} (forma canônica: chaves ordenadas,
 * indentação de 2 espaços). Mudou o código sem atualizar o arquivo → este teste falha. Para atualizar, conscientemente:
 * <pre>mvn verify -Dit.test=PublicApiContractIT -Dpublicapi.contract.update=true</pre>
 * e registre a mudança no changelog ({@code backend/src/main/resources/publicapi/changelog.json}).
 */
class PublicApiContractIT extends IntegrationTest {

    static final Path CONTRACT = Path.of("..", "docs", "api", "public-api", "v1", "openapi.json");

    @Autowired
    WebApplicationContext context;
    @Autowired
    JsonCodec json;

    @Test
    void generatedContractMatchesTheVersionedFile() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        ActorAuthentication user = ActorAuthentication.of(new Actor(Actor.ActorType.USER, Ids.newId(), Ids.newId(),
                Ids.newId(), "doc", Set.of(), CreditorScope.NONE));
        String generated = mvc.perform(get("/v3/api-docs/public-v1").with(authentication(user)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String canonical = canonical(generated);

        assertThat(canonical).contains("\"/public-api/v1/titles\"").contains("integrationToken")
                .doesNotContain("\"/api/v1/");
        if (Boolean.getBoolean("publicapi.contract.update")) {
            Files.createDirectories(CONTRACT.getParent());
            Files.writeString(CONTRACT, canonical, StandardCharsets.UTF_8);
        }
        assertThat(Files.exists(CONTRACT)).as("contrato versionado %s (gere com -Dpublicapi.contract.update=true)", CONTRACT)
                .isTrue();
        assertThat(Files.readString(CONTRACT, StandardCharsets.UTF_8))
                .as("O contrato da API pública mudou sem atualizar docs/api/public-api/v1/openapi.json; "
                        + "rode com -Dpublicapi.contract.update=true e registre no changelog")
                .isEqualTo(canonical);
    }

    private String canonical(String raw) {
        Object tree = json.mapper().readValue(raw, new TypeReference<Object>() {
        });
        return json.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(sorted(tree)) + "\n";
    }

    @SuppressWarnings("unchecked")
    private static Object sorted(Object node) {
        if (node instanceof Map<?, ?> map) {
            TreeMap<String, Object> result = new TreeMap<>();
            ((Map<String, Object>) map).forEach((k, v) -> result.put(k, sorted(v)));
            return result;
        }
        if (node instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            list.forEach(v -> result.add(sorted(v)));
            return result;
        }
        return node;
    }
}
