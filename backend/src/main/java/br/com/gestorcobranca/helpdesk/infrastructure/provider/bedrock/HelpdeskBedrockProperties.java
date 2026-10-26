package br.com.gestorcobranca.helpdesk.infrastructure.provider.bedrock;

import br.com.gestorcobranca.shared.ai.bedrock.BedrockClientSettings;
import br.com.gestorcobranca.shared.ai.bedrock.BedrockModelSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * Amazon Bedrock do chat de suporte ({@code app.helpdesk.bedrock.*}): um único nível, Claude Haiku 5.5 (aprovado para
 * o chat). Mesma guarda de transferência internacional do assistente: sem a aprovação explícita
 * ({@code international-transfer-approved}), a aplicação não sobe com o provedor ligado. Sem credenciais: papel IAM
 * da instância.
 *
 * @param enabled liga o provedor (exclusivo com {@code app.helpdesk.sandbox.enabled})
 * @param model   modelo ou perfil de inferência, cache de prompt e preços
 */
@ConfigurationProperties(prefix = "app.helpdesk.bedrock")
public record HelpdeskBedrockProperties(boolean enabled, String region, boolean internationalTransferApproved,
                                        URI endpointOverride, Duration connectTimeout, Duration socketTimeout,
                                        Integer maxConnections, Duration retryBackoff, String currency, String cacheTtl,
                                        BedrockModelSettings model) {

    static final String PREFIX = "app.helpdesk.bedrock";

    public BedrockClientSettings client() {
        return new BedrockClientSettings(region, internationalTransferApproved, endpointOverride, connectTimeout,
                socketTimeout, maxConnections, retryBackoff, currency, cacheTtl);
    }
}
