package br.com.gestorcobranca.ingestion.infrastructure.provider.bedrock;

import br.com.gestorcobranca.ingestion.application.port.out.PdfModelPort;
import br.com.gestorcobranca.shared.ai.ModelTier;
import br.com.gestorcobranca.shared.ai.bedrock.BedrockConverseModel;
import br.com.gestorcobranca.shared.ai.bedrock.ConverseTranslator;
import io.micrometer.core.instrument.MeterRegistry;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

import java.util.Map;

/**
 * Adaptador da importação PDF para o Amazon Bedrock: o cliente Converse do kernel ({@link BedrockConverseModel} —
 * tradução, imagens, prazo, retentativa única, erros, tokens e custo) com o modelo configurado e as métricas
 * {@code ingestion.pdf.bedrock.*}.
 */
final class BedrockPdfModel implements PdfModelPort, AutoCloseable {

    static final String METRIC_PREFIX = "ingestion.pdf.bedrock";
    static final String CONTEXT_HEADER = "CONTEXTO DA IMPORTAÇÃO (JSON gerado pelo sistema nesta chamada: campos do "
            + "layout canônico e regras do mapa):\n";

    private final BedrockConverseModel delegate;

    BedrockPdfModel(BedrockRuntimeClient client, PdfBedrockProperties properties, ConverseTranslator translator,
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
