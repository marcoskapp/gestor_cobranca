package br.com.gestorcobranca.helpdesk.infrastructure.provider.bedrock;

import br.com.gestorcobranca.shared.ai.bedrock.BedrockRuntimeClients;
import br.com.gestorcobranca.shared.ai.bedrock.BedrockRuntimeClients.KeyedModel;
import br.com.gestorcobranca.shared.ai.bedrock.ConverseTranslator;
import br.com.gestorcobranca.shared.json.JsonCodec;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Liga o Bedrock do chat de suporte ({@code app.helpdesk.bedrock.enabled=true}). Validação na subida pelo kernel
 * (região, modelo, preços e a aprovação da transferência internacional): inválida, a aplicação não sobe. Cliente
 * próprio do chat (não exposto como bean), fechado com o adaptador.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.helpdesk.bedrock", name = "enabled", havingValue = "true")
class HelpdeskBedrockConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(HelpdeskBedrockConfiguration.class);

    @Bean
    BedrockHelpdeskModel bedrockHelpdeskModel(HelpdeskBedrockProperties properties, JsonCodec json, MeterRegistry meters) {
        BedrockRuntimeClients.requireValid(HelpdeskBedrockProperties.PREFIX, properties.client(),
                List.of(new KeyedModel(HelpdeskBedrockProperties.PREFIX + ".model", properties.model())));
        LOG.atInfo().addKeyValue("provider", "bedrock").addKeyValue("product", "helpdesk")
                .addKeyValue("region", properties.region()).addKeyValue("model", properties.model().modelId())
                .log("Cliente do Amazon Bedrock do chat de suporte configurado (transferência internacional aprovada)");
        return new BedrockHelpdeskModel(BedrockRuntimeClients.create(properties.client()), properties,
                new ConverseTranslator(json, BedrockHelpdeskModel.CONTEXT_HEADER), meters, d -> Thread.sleep(d));
    }
}
