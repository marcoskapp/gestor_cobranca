package br.com.gestorcobranca.assistant.infrastructure.provider.bedrock;

import br.com.gestorcobranca.assistant.application.port.out.LanguageModelPort;
import br.com.gestorcobranca.shared.ai.ModelTier;
import br.com.gestorcobranca.shared.ai.bedrock.BedrockConverseModel;
import br.com.gestorcobranca.shared.ai.bedrock.ConverseTranslator;
import io.micrometer.core.instrument.MeterRegistry;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

import java.util.Map;

/**
 * Adaptador da {@link LanguageModelPort} do assistente para o <strong>Amazon Bedrock</strong>: os dois níveis do
 * assistente ({@code STANDARD} = Claude Haiku, {@code ADVANCED} = Claude Sonnet) sobre o cliente Converse do kernel
 * ({@link BedrockConverseModel} — tradução, cache de prompt, prazo, retentativa única, erros, custo), com as métricas
 * {@code assistant.bedrock.*}. Habilitado por {@code app.assistant.bedrock.enabled=true} ({@link BedrockConfiguration}).
 * Falha do provedor: o turno transfere para humanos (orquestrador).
 */
final class BedrockLanguageModel implements LanguageModelPort, AutoCloseable {

    static final String PROVIDER = BedrockConverseModel.PROVIDER;
    static final String METRIC_PREFIX = "assistant.bedrock";
    static final String CONTEXT_HEADER = "CONTEXTO DO ATENDIMENTO (JSON gerado pelo sistema nesta chamada; "
            + "fonte única de valores, datas, ofertas e regras do turno):\n";

    private final BedrockConverseModel delegate;

    BedrockLanguageModel(BedrockRuntimeClient client, BedrockProperties properties, ConverseTranslator translator,
                         MeterRegistry meters, BedrockConverseModel.Pause pause) {
        this.delegate = new BedrockConverseModel(client, properties.client(),
                Map.of(ModelTier.STANDARD, properties.standard(), ModelTier.ADVANCED, properties.advanced()),
                translator, meters, METRIC_PREFIX, pause);
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        return delegate.complete(request);
    }

    /** Fecha o cliente HTTP do Bedrock na parada da aplicação (método de destruição inferido pelo Spring). */
    @Override
    public void close() {
        delegate.close();
    }
}
