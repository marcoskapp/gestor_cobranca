package br.com.gestorcobranca.assistant.infrastructure.config;

import br.com.gestorcobranca.assistant.application.AssistantProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Valida a escolha do provedor de IA na subida: no máximo um provedor por instância — o simulado
 * ({@code app.assistant.sandbox.enabled}) e o Amazon Bedrock ({@code app.assistant.bedrock.enabled}) são
 * mutuamente exclusivos — e o simulado nunca no perfil {@code prod}. A configuração do Bedrock em si
 * (região, modelos, preços, residência) é validada pelo adaptador ({@code BedrockConfiguration}). Em qualquer
 * erro, a aplicação não sobe. Sem provedor, o assistente transfere para humanos as conversas que conduziria.
 */
@Component
class AssistantProviderGuard {

    static final String BEDROCK_ENABLED = "app.assistant.bedrock.enabled";
    private static final Logger LOG = LoggerFactory.getLogger(AssistantProviderGuard.class);

    AssistantProviderGuard(AssistantProperties properties, Environment environment) {
        boolean sandbox = properties.sandbox().enabled();
        boolean bedrock = Binder.get(environment).bind(BEDROCK_ENABLED, Boolean.class).orElse(false);
        if (sandbox && bedrock) {
            throw new IllegalStateException("app.assistant.sandbox.enabled e " + BEDROCK_ENABLED
                    + " são mutuamente exclusivos: habilite um único provedor de IA");
        }
        if (sandbox) {
            if (environment.acceptsProfiles(Profiles.of("prod"))) {
                throw new IllegalStateException("app.assistant.sandbox.enabled não é permitido no perfil prod");
            }
            LOG.atWarn().addKeyValue("provider", "sandbox")
                    .log("Modelo de linguagem simulado (sandbox) do assistente habilitado — somente para ambiente local/teste");
        } else if (bedrock) {
            LOG.atInfo().addKeyValue("provider", "bedrock").log("Provedor de IA do assistente: Amazon Bedrock");
        } else {
            LOG.atInfo().addKeyValue("provider", "none")
                    .log("Assistente sem provedor de IA: conversas que ele conduziria são transferidas para humanos");
        }
    }
}
