package br.com.gestorcobranca.helpdesk.infrastructure.config;

import br.com.gestorcobranca.helpdesk.application.HelpdeskProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Valida a escolha do provedor de IA do chat de suporte na subida: no máximo um por instância — o simulado
 * ({@code app.helpdesk.sandbox.enabled}) e o Amazon Bedrock ({@code app.helpdesk.bedrock.enabled}) são mutuamente
 * exclusivos — e o simulado nunca no perfil {@code prod}. A configuração do Bedrock em si (região, modelo, preços e a
 * aprovação da transferência internacional) é validada pelo kernel ao criar o cliente. Em qualquer erro, a aplicação
 * não sobe. Sem provedor, o chat responde que está indisponível (sem quebrar a interface).
 */
@Component
class HelpdeskProviderGuard {

    static final String BEDROCK_ENABLED = "app.helpdesk.bedrock.enabled";
    private static final Logger LOG = LoggerFactory.getLogger(HelpdeskProviderGuard.class);

    HelpdeskProviderGuard(HelpdeskProperties properties, Environment environment) {
        boolean sandbox = properties.sandbox().enabled();
        boolean bedrock = Binder.get(environment).bind(BEDROCK_ENABLED, Boolean.class).orElse(false);
        if (sandbox && bedrock) {
            throw new IllegalStateException("app.helpdesk.sandbox.enabled e " + BEDROCK_ENABLED
                    + " são mutuamente exclusivos: habilite um único provedor de IA para o chat de suporte");
        }
        if (sandbox) {
            if (environment.acceptsProfiles(Profiles.of("prod"))) {
                throw new IllegalStateException("app.helpdesk.sandbox.enabled não é permitido no perfil prod");
            }
            LOG.atWarn().addKeyValue("provider", "sandbox").addKeyValue("product", "helpdesk")
                    .log("Modelo simulado do chat de suporte habilitado — somente para ambiente local/teste");
        } else if (bedrock) {
            LOG.atInfo().addKeyValue("provider", "bedrock").addKeyValue("product", "helpdesk")
                    .log("Provedor de IA do chat de suporte: Amazon Bedrock");
        } else {
            LOG.atInfo().addKeyValue("provider", "none").addKeyValue("product", "helpdesk")
                    .log("Chat de suporte sem provedor de IA: indisponível neste ambiente");
        }
    }
}
