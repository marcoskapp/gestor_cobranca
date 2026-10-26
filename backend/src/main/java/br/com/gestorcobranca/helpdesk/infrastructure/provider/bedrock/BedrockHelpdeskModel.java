package br.com.gestorcobranca.helpdesk.infrastructure.provider.bedrock;

import br.com.gestorcobranca.helpdesk.application.port.out.HelpdeskModelPort;
import br.com.gestorcobranca.shared.ai.ModelTier;
import br.com.gestorcobranca.shared.ai.bedrock.BedrockConverseModel;
import br.com.gestorcobranca.shared.ai.bedrock.ConverseTranslator;
import io.micrometer.core.instrument.MeterRegistry;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

import java.util.Map;

/**
 * Adaptador do chat de suporte para o Amazon Bedrock: o cliente Converse do kernel ({@link BedrockConverseModel} —
 * tradução, cache de prompt, prazo, retentativa única, erros e custo) com o modelo do chat (Claude Haiku 5.5) e as
 * métricas {@code helpdesk.bedrock.*}. O servidor sempre pede o nível padrão.
 */
final class BedrockHelpdeskModel implements HelpdeskModelPort, AutoCloseable {

    static final String METRIC_PREFIX = "helpdesk.bedrock";
    static final String CONTEXT_HEADER = "CONTEXTO DO SUPORTE (JSON gerado pelo sistema nesta chamada: quem pergunta, "
            + "tela atual, manual e telas permitidas; é a fonte das regras do sistema):\n";

    private final BedrockConverseModel delegate;

    BedrockHelpdeskModel(BedrockRuntimeClient client, HelpdeskBedrockProperties properties, ConverseTranslator translator,
                         MeterRegistry meters, BedrockConverseModel.Pause pause) {
        this.delegate = new BedrockConverseModel(client, properties.client(), Map.of(ModelTier.STANDARD, properties.model()),
                translator, meters, METRIC_PREFIX, pause);
    }

    @Override
    public String provider() {
        return BedrockConverseModel.PROVIDER;
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        return delegate.complete(request);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
