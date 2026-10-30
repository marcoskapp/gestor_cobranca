package br.com.gestorcobranca.ingestion.infrastructure.provider.bedrock;

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
 * Liga o Bedrock da importação PDF ({@code app.ingestion.pdf.bedrock.enabled=true}). Validação na subida pelo kernel
 * (região, modelo, preços e a aprovação da transferência internacional): inválida, a aplicação não sobe. Cliente
 * próprio (não exposto como bean), fechado com o adaptador. Desligado, a importação PDF funciona com os mapas salvos do
 * credor e com o mapa escrito pelo usuário; PDF sem camada de texto não pode ser lido.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.ingestion.pdf.bedrock", name = "enabled", havingValue = "true")
class PdfBedrockConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(PdfBedrockConfiguration.class);

    @Bean
    BedrockPdfModel bedrockPdfModel(PdfBedrockProperties properties, JsonCodec json, MeterRegistry meters) {
        BedrockRuntimeClients.requireValid(PdfBedrockProperties.PREFIX, properties.client(),
                List.of(new KeyedModel(PdfBedrockProperties.PREFIX + ".model", properties.model())));
        LOG.atInfo().addKeyValue("provider", "bedrock").addKeyValue("product", "ingestion.pdf")
                .addKeyValue("region", properties.region()).addKeyValue("model", properties.model().modelId())
                .log("Cliente do Amazon Bedrock da importação PDF configurado (transferência internacional aprovada)");
        return new BedrockPdfModel(BedrockRuntimeClients.create(properties.client()), properties,
                new ConverseTranslator(json, BedrockPdfModel.CONTEXT_HEADER), meters, d -> Thread.sleep(d));
    }
}
