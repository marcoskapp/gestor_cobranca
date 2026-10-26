package br.com.gestorcobranca.assistant.infrastructure.provider.bedrock;

import br.com.gestorcobranca.shared.json.JsonCodec;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClientBuilder;

import java.util.List;

/**
 * Liga o adaptador Amazon Bedrock ({@code app.assistant.bedrock.enabled=true}). A configuração é validada na
 * subida ({@link BedrockProperties#validate()}): região, modelo e preços dos dois níveis e a aprovação da
 * transferência internacional — inválida, a aplicação não sobe. Credenciais pela cadeia padrão do SDK (papel IAM
 * da instância EC2; nunca em arquivo). Retentativas do SDK desligadas: a única retentativa permitida pelo
 * contrato é feita pelo adaptador, dentro do prazo da chamada.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.assistant.bedrock", name = "enabled", havingValue = "true")
class BedrockConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(BedrockConfiguration.class);

    @Bean(destroyMethod = "close")
    BedrockRuntimeClient bedrockRuntimeClient(BedrockProperties properties) {
        List<String> problems = properties.validate();
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Configuração do Amazon Bedrock inválida: " + String.join("; ", problems));
        }
        BedrockRuntimeClientBuilder builder = BedrockRuntimeClient.builder()
                .region(Region.of(properties.region().trim()))
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .httpClientBuilder(Apache5HttpClient.builder()
                        .connectionTimeout(properties.connectTimeout())
                        .socketTimeout(properties.socketTimeout())
                        .maxConnections(properties.maxConnections()))
                .overrideConfiguration(o -> o.retryStrategy(AwsRetryStrategy.doNotRetry()));
        if (properties.endpointOverride() != null) {
            builder.endpointOverride(properties.endpointOverride());
        }
        LOG.atInfo().addKeyValue("provider", BedrockLanguageModel.PROVIDER).addKeyValue("region", properties.region())
                .addKeyValue("standardModel", properties.standard().modelId())
                .addKeyValue("advancedModel", properties.advanced().modelId())
                .addKeyValue("cacheTtl", properties.cacheTtl())
                .log("Cliente do Amazon Bedrock configurado (transferência internacional aprovada na configuração)");
        return builder.build();
    }

    @Bean
    BedrockLanguageModel bedrockLanguageModel(BedrockRuntimeClient client, BedrockProperties properties, JsonCodec json,
                                              MeterRegistry meters) {
        return new BedrockLanguageModel(client, properties, new ConverseTranslator(json), meters, d -> Thread.sleep(d));
    }
}
