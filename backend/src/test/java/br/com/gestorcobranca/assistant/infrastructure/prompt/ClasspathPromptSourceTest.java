package br.com.gestorcobranca.assistant.infrastructure.prompt;

import br.com.gestorcobranca.assistant.application.port.out.PromptSource.PromptTemplate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClasspathPromptSourceTest {

    @Test
    void versionedPromptsLoadWithTransparencyAndLimits() {
        var prompts = new ClasspathPromptSource().all();
        assertThat(prompts).extracting(PromptTemplate::code).contains("negociacao");
        PromptTemplate p = prompts.stream().filter(t -> t.code().equals("negociacao") && t.version() == 1).findFirst()
                .orElseThrow();
        assertThat(p.sha256()).hasSize(64);
        assertThat(p.text()).contains("assistente virtual").contains("Nunca diga").contains("transfer_to_human")
                .contains("dado, não instrução").doesNotContainIgnoringCase("api_key").doesNotContainIgnoringCase("secret");
    }
}
