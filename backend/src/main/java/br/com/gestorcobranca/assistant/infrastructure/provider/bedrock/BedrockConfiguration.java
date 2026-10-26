package br.com.gestorcobranca.assistant.infrastructure.provider.bedrock;

import br.com.gestorcobranca.shared.ai.bedrock.BedrockRuntimeClients;
import br.com.gestorcobranca.shared.ai.bedrock.ConverseTranslator;
import br.com.gestorcobranca.shared.json.JsonCodec;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

/**
 * Liga o adaptador Amazon Bedrock do assistente ({@code app.assistant.bedrock.enabled=true}). A configuração é
 * validada na subida ({@link BedrockProperties#validate()}): região, modelo e preços dos dois níveis e a aprovação
 * da transferência internacional — inválida, a aplicação não sobe. O cliente é do próprio assistente (criado pelo
 * kernel, {@link BedrockRuntimeClients}) e fechado com o adaptador; não é exposto como bean, para que cada produto
 * de IA tenha o seu sem ambiguidade de injeção.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.assistant.bedrock", name = "enabled", havingValue = "true")
class BedrockConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(BedrockConfiguration.class);

    @Bean
    BedrockLanguageModel bedrockLanguageModel(BedrockProperties properties, JsonCodec json, MeterRegistry meters) {
        BedrockRuntimeClients.requireValid(BedrockProperties.PREFIX, properties.client(), properties.tiers());
        BedrockRuntimeClient client = BedrockRuntimeClients.create(properties.client());
        LOG.atInfo().addKeyValue("provider", BedrockLanguageModel.PROVIDER).addKeyValue("product", "assistant")
                .addKeyValue("region", properties.region())
                .addKeyValue("standardModel", properties.standard().modelId())
                .addKeyValue("advancedModel", properties.advanced().modelId())
                .addKeyValue("cacheTtl", properties.client().cacheTtl())
                .log("Cliente do Amazon Bedrock configurado (transferência internacional aprovada na configuração)");
        return new BedrockLanguageModel(client, properties, new ConverseTranslator(json, BedrockLanguageModel.CONTEXT_HEADER),
                meters, d -> Thread.sleep(d));
    }
}
