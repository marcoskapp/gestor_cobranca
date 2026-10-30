package br.com.gestorcobranca.ingestion.infrastructure.provider.bedrock;

import br.com.gestorcobranca.shared.ai.bedrock.BedrockClientSettings;
import br.com.gestorcobranca.shared.ai.bedrock.BedrockModelSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

/**
 * Amazon Bedrock da importação PDF inteligente ({@code app.ingestion.pdf.bedrock.*}): um modelo (Claude Sonnet, que lê
 * texto e imagem). Mesma guarda de transferência internacional do assistente e do chat de suporte: sem a aprovação
 * explícita ({@code international-transfer-approved}), a aplicação não sobe com o provedor ligado. Sem credenciais:
 * papel IAM da instância.
 */
@ConfigurationProperties(prefix = "app.ingestion.pdf.bedrock")
public record PdfBedrockProperties(boolean enabled, String region, boolean internationalTransferApproved,
                                   URI endpointOverride, Duration connectTimeout, Duration socketTimeout,
                                   Integer maxConnections, Duration retryBackoff, String currency, String cacheTtl,
                                   BedrockModelSettings model) {

    static final String PREFIX = "app.ingestion.pdf.bedrock";

    public BedrockClientSettings client() {
        return new BedrockClientSettings(region, internationalTransferApproved, endpointOverride, connectTimeout,
                socketTimeout, maxConnections, retryBackoff, currency, cacheTtl);
    }
}
