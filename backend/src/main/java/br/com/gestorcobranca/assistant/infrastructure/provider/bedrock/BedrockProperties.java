package br.com.gestorcobranca.assistant.infrastructure.provider.bedrock;

import br.com.gestorcobranca.shared.ai.ModelTier;
import br.com.gestorcobranca.shared.ai.bedrock.BedrockClientSettings;
import br.com.gestorcobranca.shared.ai.bedrock.BedrockModelSettings;
import br.com.gestorcobranca.shared.ai.bedrock.BedrockRuntimeClients.KeyedModel;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Parâmetros do adaptador Amazon Bedrock do assistente ({@code app.assistant.bedrock.*}). Sem credenciais: o SDK
 * usa a cadeia padrão (papel IAM da instância EC2). Os preços ficam aqui, nunca no código, e precisam ser
 * conferidos na conta. Cliente, validação, tradução, preço e erros são os do kernel
 * ({@code shared.ai.bedrock}), comuns aos produtos de IA; aqui ficam os dois níveis do assistente.
 *
 * @param enabled                       liga o adaptador (exclusivo com {@code app.assistant.sandbox.enabled})
 * @param region                        região do endpoint {@code bedrock-runtime} chamado (o perfil de inferência
 *                                      decide onde o modelo processa — ver docs/tecnico/desenho/assistente.md §6.5)
 * @param internationalTransferApproved confirmação de que o responsável aprovou a transferência internacional
 *                                      dos dados do turno (LGPD art. 33) para a região/perfil configurados: nenhum
 *                                      modelo Claude processa no Brasil, então sem ela o adaptador não sobe
 * @param standard                      nível {@link ModelTier#STANDARD}
 * @param advanced                      nível {@link ModelTier#ADVANCED}
 */
@ConfigurationProperties(prefix = "app.assistant.bedrock")
public record BedrockProperties(boolean enabled, String region, boolean internationalTransferApproved,
                                URI endpointOverride, Duration connectTimeout, Duration socketTimeout,
                                Integer maxConnections, Duration retryBackoff, String currency, String cacheTtl,
                                BedrockModelSettings standard, BedrockModelSettings advanced) {

    static final String PREFIX = "app.assistant.bedrock";

    /** Cliente com os padrões do kernel aplicados (conexão 3 s, socket 65 s, 50 conexões, 400 ms, USD, 5m). */
    public BedrockClientSettings client() {
        return new BedrockClientSettings(region, internationalTransferApproved, endpointOverride, connectTimeout,
                socketTimeout, maxConnections, retryBackoff, currency, cacheTtl);
    }

    public String currency() {
        return client().currency();
    }

    public BedrockModelSettings tier(ModelTier tier) {
        return tier == ModelTier.ADVANCED ? advanced : standard;
    }

    List<KeyedModel> tiers() {
        return List.of(new KeyedModel(PREFIX + ".standard", standard), new KeyedModel(PREFIX + ".advanced", advanced));
    }

    /** @return problemas de configuração (vazio = válida) */
    public List<String> validate() {
        List<String> problems = new ArrayList<>();
        client().validate(PREFIX, problems);
        for (KeyedModel t : tiers()) {
            BedrockModelSettings.validate(t.key(), t.settings(), problems);
        }
        return problems;
    }
}
