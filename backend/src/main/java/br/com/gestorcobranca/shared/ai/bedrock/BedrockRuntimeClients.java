package br.com.gestorcobranca.shared.ai.bedrock;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClientBuilder;

import java.util.ArrayList;
import java.util.List;

/**
 * Criação do cliente {@code bedrock-runtime} de um produto de IA, sempre depois da validação da configuração
 * (inclusive a aprovação da transferência internacional): inválida, a aplicação não sobe. Credenciais pela cadeia
 * padrão do SDK (papel IAM da instância EC2; nunca em arquivo). Retentativas do SDK desligadas: a única
 * retentativa permitida pelo contrato é feita por {@link BedrockConverseModel}, dentro do prazo da chamada.
 */
public final class BedrockRuntimeClients {

    private BedrockRuntimeClients() {
    }

    /**
     * @param prefix prefixo das chaves (mensagens de erro)
     * @param tiers  níveis de modelo do produto, com a chave de cada um
     * @throws IllegalStateException com todos os problemas encontrados
     */
    public static void requireValid(String prefix, BedrockClientSettings client, List<KeyedModel> tiers) {
        List<String> problems = new ArrayList<>();
        client.validate(prefix, problems);
        for (KeyedModel tier : tiers) {
            BedrockModelSettings.validate(tier.key(), tier.settings(), problems);
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Configuração do Amazon Bedrock inválida: " + String.join("; ", problems));
        }
    }

    public static BedrockRuntimeClient create(BedrockClientSettings settings) {
        BedrockRuntimeClientBuilder builder = BedrockRuntimeClient.builder()
                .region(Region.of(settings.region().trim()))
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .httpClientBuilder(Apache5HttpClient.builder()
                        .connectionTimeout(settings.connectTimeout())
                        .socketTimeout(settings.socketTimeout())
                        .maxConnections(settings.maxConnections()))
                .overrideConfiguration(o -> o.retryStrategy(AwsRetryStrategy.doNotRetry()));
        if (settings.endpointOverride() != null) {
            builder.endpointOverride(settings.endpointOverride());
        }
        return builder.build();
    }

    /** Nível de modelo com a chave completa na configuração (para as mensagens de validação). */
    public record KeyedModel(String key, BedrockModelSettings settings) {
    }
}
