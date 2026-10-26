package br.com.gestorcobranca.shared.ai.bedrock;

import br.com.gestorcobranca.shared.ai.LanguageModel;
import br.com.gestorcobranca.shared.ai.ModelTier;
import br.com.gestorcobranca.shared.logging.SensitiveDataMasker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.awscore.AwsRequestOverrideConfiguration;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.ApiCallAttemptTimeoutException;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.AccessDeniedException;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.InternalServerException;
import software.amazon.awssdk.services.bedrockruntime.model.ModelErrorException;
import software.amazon.awssdk.services.bedrockruntime.model.ModelNotReadyException;
import software.amazon.awssdk.services.bedrockruntime.model.ResourceNotFoundException;
import software.amazon.awssdk.services.bedrockruntime.model.ServiceQuotaExceededException;
import software.amazon.awssdk.services.bedrockruntime.model.ServiceUnavailableException;
import software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException;
import software.amazon.awssdk.services.bedrockruntime.model.TokenUsage;
import software.amazon.awssdk.services.bedrockruntime.model.ValidationException;

import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Adaptador da {@link LanguageModel} para o <strong>Amazon Bedrock</strong> (Converse API, modelos Claude), comum aos
 * produtos de IA: cada produto cria a sua instância com a própria configuração de níveis, o próprio prefixo de
 * métricas e o próprio cabeçalho de contexto (assistente: {@code assistant.bedrock.*}; chat de suporte:
 * {@code helpdesk.bedrock.*}).
 * <ul>
 *   <li><strong>Nível → modelo</strong>: cada {@link ModelTier} vira o modelo ou perfil de inferência configurado
 *       pelo produto; nível sem configuração usa o {@link ModelTier#STANDARD};</li>
 *   <li><strong>Tradução</strong> e <strong>cache de prompt</strong>: {@link ConverseTranslator};</li>
 *   <li><strong>Prazo</strong>: {@code request.timeout()} é o tempo total da chamada, inclusive a retentativa
 *       ({@code apiCallTimeout}/{@code apiCallAttemptTimeout} com o prazo restante); o SDK não repete sozinho;</li>
 *   <li><strong>Retentativa</strong>: no máximo uma, para limite de taxa e 5xx (inclusive modelo ainda não pronto),
 *       se ainda houver prazo;</li>
 *   <li><strong>Erros</strong>: tempo esgotado → {@link ModelTimeoutException}; limite de taxa, 5xx, rede e saída
 *       malformada → {@link ModelUnavailableException}; validação, acesso negado, modelo inexistente ou não
 *       habilitado, cota e janela de contexto → {@link ModelRejectedException}; filtro de conteúdo ou guardrail →
 *       {@link StopReason#CONTENT_FILTERED};</li>
 *   <li><strong>Resposta</strong>: texto, chamadas de ferramenta (ids do Bedrock), tokens com e sem cache, custo
 *       pela tabela configurada ({@link BedrockPricing}), modelo usado e latência total;</li>
 *   <li><strong>Observabilidade</strong>: métricas {@code <prefixo>.calls|latency|retries|tokens|cost|stop_reasons|
 *       long_context} e {@code integration.calls{provider=bedrock,operation=converse}} (docs/tecnico/logs-e-metricas.md
 *       §5); logs técnicos só com produto, modelo, nível, códigos e ids da AWS — nunca conteúdo da conversa (a
 *       mensagem de erro da AWS vai mascarada e truncada).</li>
 * </ul>
 */
public final class BedrockConverseModel implements LanguageModel, AutoCloseable {

    public static final String PROVIDER = "bedrock";
    private static final Logger LOG = LoggerFactory.getLogger(BedrockConverseModel.class);
    private static final java.util.regex.Pattern DIGITS = java.util.regex.Pattern.compile("\\d{4,}");

    /** Espera antes da retentativa (substituível nos testes). */
    @FunctionalInterface
    public interface Pause {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final BedrockRuntimeClient client;
    private final BedrockClientSettings settings;
    private final Map<ModelTier, BedrockModelSettings> tiers;
    private final ConverseTranslator translator;
    private final MeterRegistry meters;
    private final String metricPrefix;
    private final Pause pause;

    /**
     * @param tiers        modelo de cada nível usado pelo produto (ao menos {@link ModelTier#STANDARD})
     * @param metricPrefix prefixo das métricas do produto (ex.: {@code assistant.bedrock})
     */
    public BedrockConverseModel(BedrockRuntimeClient client, BedrockClientSettings settings,
                                Map<ModelTier, BedrockModelSettings> tiers, ConverseTranslator translator,
                                MeterRegistry meters, String metricPrefix, Pause pause) {
        if (!tiers.containsKey(ModelTier.STANDARD)) {
            throw new IllegalArgumentException("Nível STANDARD obrigatório");
        }
        this.client = client;
        this.settings = settings;
        this.tiers = new EnumMap<>(tiers);
        this.translator = translator;
        this.meters = meters;
        this.metricPrefix = metricPrefix;
        this.pause = pause;
    }

    /** Fecha o cliente HTTP (o cliente é exclusivo desta instância). */
    @Override
    public void close() {
        client.close();
    }

    private BedrockModelSettings tier(ModelTier tier) {
        return tiers.getOrDefault(tier, tiers.get(ModelTier.STANDARD));
    }

    @Override
    public String provider() {
        return PROVIDER;
    }

    @Override
    public ModelResponse complete(ModelRequest request) {
        ModelTier tier = request.tier();
        BedrockModelSettings config = tier(tier);
        String model = config.modelId().trim();
        ConverseRequest base = translator.toConverse(request, model, config.caching(), settings.cacheTtl());
        long started = System.nanoTime();
        long deadline = started + request.timeout().toNanos();
        for (int attempt = 1; ; attempt++) {
            Duration remaining = Duration.ofNanos(deadline - System.nanoTime());
            if (remaining.isNegative() || remaining.isZero()) {
                throw failed(model, tier, started, attempt, null,
                        new ModelTimeoutException("Prazo da chamada ao Bedrock esgotado antes da retentativa", null),
                        "DeadlineExceeded");
            }
            ConverseRequest call = base.toBuilder().overrideConfiguration(AwsRequestOverrideConfiguration.builder()
                    .apiCallTimeout(remaining).apiCallAttemptTimeout(remaining).build()).build();
            ConverseResponse response;
            try {
                response = client.converse(call);
            } catch (AwsServiceException | SdkClientException e) {
                Duration left = Duration.ofNanos(deadline - System.nanoTime());
                if (attempt == 1 && retryable(e) && left.compareTo(settings.retryBackoff()) > 0) {
                    meters.counter(metricPrefix + ".retries", "model", model, "reason", errorCode(e)).increment();
                    LOG.atWarn().addKeyValue("provider", PROVIDER).addKeyValue("product", metricPrefix).addKeyValue("model", model).addKeyValue("tier", tier)
                            .addKeyValue("errorCode", errorCode(e)).addKeyValue("awsRequestId", requestId(e))
                            .log("Bedrock recusou a chamada por limite de taxa/indisponibilidade; repetindo uma vez");
                    pauseBeforeRetry();
                    continue;
                }
                throw failed(model, tier, started, attempt, e, map(e), errorCode(e));
            }
            try {
                return response(response, model, tier, config.pricing(), Duration.ofNanos(System.nanoTime() - started));
            } catch (ModelException e) {
                // janela de contexto excedida, saída malformada ou motivo de parada desconhecido
                throw failed(model, tier, started, attempt, null, e,
                        response.stopReasonAsString() == null ? "NoStopReason" : response.stopReasonAsString());
            }
        }
    }

    // ------------------------------------------------------------------ resposta

    private ModelResponse response(ConverseResponse r, String model, ModelTier tier, BedrockModelSettings.Pricing pricing,
                                   Duration latency) {
        List<String> texts = new ArrayList<>();
        List<ToolCall> calls = new ArrayList<>();
        if (r.output() != null && r.output().message() != null) {
            for (ContentBlock b : r.output().message().content()) {
                if (b.text() != null && !b.text().isBlank()) {
                    texts.add(b.text());
                } else if (b.toolUse() != null) {
                    calls.add(new ToolCall(b.toolUse().toolUseId(), b.toolUse().name(), translator.toJson(b.toolUse().input())));
                }
            }
        }
        String text = texts.isEmpty() ? null : String.join("\n", texts);
        StopReason stop = stopReason(r, model, tier, text, calls);
        Usage usage = usage(r.usage());
        BigDecimal cost = BedrockPricing.cost(pricing, usage);
        record(model, tier, "ok", "none", latency);
        meters.counter(metricPrefix + ".stop_reasons", "model", model, "reason", stop.name()).increment();
        tokens(model, tier, "input", usage.inputTokens());
        tokens(model, tier, "output", usage.outputTokens());
        tokens(model, tier, "cache_read", usage.cacheReadInputTokens());
        tokens(model, tier, "cache_write", usage.cacheWriteInputTokens());
        if (BedrockPricing.longContext(pricing, usage)) {
            meters.counter(metricPrefix + ".long_context", "model", model).increment();
        }
        if (cost.signum() > 0) {
            meters.counter(metricPrefix + ".cost", "model", model, "tier", tier.name(), "currency", settings.currency())
                    .increment(cost.doubleValue());
        }
        return new ModelResponse(text, calls, stop, usage, new Cost(cost, settings.currency()), model, latency);
    }

    private StopReason stopReason(ConverseResponse r, String model, ModelTier tier, String text, List<ToolCall> calls) {
        if (r.stopReason() == null) {
            return calls.isEmpty() ? StopReason.END_TURN : StopReason.TOOL_USE;
        }
        return switch (r.stopReason()) {
            case TOOL_USE -> calls.isEmpty() ? StopReason.END_TURN : StopReason.TOOL_USE;
            case MAX_TOKENS -> StopReason.MAX_TOKENS;
            case GUARDRAIL_INTERVENED, CONTENT_FILTERED -> StopReason.CONTENT_FILTERED;
            case MODEL_CONTEXT_WINDOW_EXCEEDED -> throw new ModelRejectedException(
                    "Conversa excedeu a janela de contexto do modelo " + model, null);
            case MALFORMED_MODEL_OUTPUT, MALFORMED_TOOL_USE -> throw new ModelUnavailableException(
                    "Saída malformada do modelo " + model + " (" + r.stopReasonAsString() + ")", null);
            case END_TURN, STOP_SEQUENCE -> calls.isEmpty() ? StopReason.END_TURN : StopReason.TOOL_USE;
            case UNKNOWN_TO_SDK_VERSION -> {
                if (text == null && calls.isEmpty()) {
                    throw new ModelUnavailableException("Motivo de parada desconhecido: " + r.stopReasonAsString(), null);
                }
                LOG.atWarn().addKeyValue("provider", PROVIDER).addKeyValue("product", metricPrefix).addKeyValue("model", model).addKeyValue("tier", tier)
                        .addKeyValue("stopReason", r.stopReasonAsString()).log("Motivo de parada do Bedrock desconhecido pelo SDK");
                yield calls.isEmpty() ? StopReason.END_TURN : StopReason.TOOL_USE;
            }
        };
    }

    private static Usage usage(TokenUsage u) {
        if (u == null) {
            return new Usage(0, 0, 0, 0);
        }
        return new Usage(n(u.inputTokens()), n(u.outputTokens()), n(u.cacheReadInputTokens()), n(u.cacheWriteInputTokens()));
    }

    private static long n(Integer value) {
        return value == null ? 0 : value;
    }

    // ------------------------------------------------------------------ erros

    private static boolean retryable(RuntimeException e) {
        if (e instanceof ThrottlingException || e instanceof ServiceUnavailableException
                || e instanceof InternalServerException || e instanceof ModelNotReadyException) {
            return true;
        }
        if (e instanceof AccessDeniedException || e instanceof ValidationException || e instanceof ResourceNotFoundException
                || e instanceof ModelErrorException || e instanceof ServiceQuotaExceededException
                || e instanceof software.amazon.awssdk.services.bedrockruntime.model.ModelTimeoutException) {
            return false;
        }
        return e instanceof AwsServiceException s && (s.statusCode() == 429 || s.statusCode() >= 500);
    }

    public static ModelException map(RuntimeException e) {
        String code = errorCode(e);
        if (e instanceof ApiCallTimeoutException || e instanceof ApiCallAttemptTimeoutException
                || e instanceof software.amazon.awssdk.services.bedrockruntime.model.ModelTimeoutException
                || (e instanceof SdkClientException && causedBy(e, SocketTimeoutException.class))) {
            return new ModelTimeoutException("Tempo esgotado na chamada ao Bedrock (" + code + ")", e);
        }
        if (e instanceof ThrottlingException || e instanceof ServiceUnavailableException
                || e instanceof InternalServerException || e instanceof ModelNotReadyException
                || e instanceof ModelErrorException) {
            return new ModelUnavailableException("Bedrock indisponível (" + code + ")", e);
        }
        if (e instanceof AccessDeniedException || e instanceof ValidationException || e instanceof ResourceNotFoundException
                || e instanceof ServiceQuotaExceededException) {
            return new ModelRejectedException("Bedrock recusou a chamada (" + code + ")", e);
        }
        if (e instanceof AwsServiceException s) {
            return s.statusCode() == 429 || s.statusCode() >= 500
                    ? new ModelUnavailableException("Bedrock indisponível (" + code + ")", e)
                    : new ModelRejectedException("Bedrock recusou a chamada (" + code + ")", e);
        }
        return new ModelUnavailableException("Falha de comunicação com o Bedrock (" + code + ")", e);
    }

    private ModelException failed(String model, ModelTier tier, long started, int attempt, RuntimeException cause,
                                  ModelException mapped, String code) {
        record(model, tier, mapped.code(), code, Duration.ofNanos(System.nanoTime() - started));
        var log = LOG.atWarn().addKeyValue("provider", PROVIDER).addKeyValue("product", metricPrefix).addKeyValue("model", model).addKeyValue("tier", tier)
                .addKeyValue("outcome", mapped.code()).addKeyValue("errorCode", code).addKeyValue("attempts", attempt);
        if (cause instanceof AwsServiceException s) {
            // a mensagem da AWS descreve a recusa (pode citar um trecho do pedido): mascarada e truncada
            String detail = s.awsErrorDetails() == null ? null : s.awsErrorDetails().errorMessage();
            log = log.addKeyValue("status", s.statusCode()).addKeyValue("awsRequestId", s.requestId())
                    .addKeyValue("detail", detail == null ? null : truncate(DIGITS.matcher(SensitiveDataMasker.mask(detail)).replaceAll("#")));
        }
        log.log("Chamada ao Bedrock falhou");
        return mapped;
    }

    private static String errorCode(RuntimeException e) {
        if (e instanceof AwsServiceException s && s.awsErrorDetails() != null && s.awsErrorDetails().errorCode() != null) {
            return s.awsErrorDetails().errorCode();
        }
        return e.getClass().getSimpleName();
    }

    private static String requestId(RuntimeException e) {
        return e instanceof AwsServiceException s ? s.requestId() : null;
    }

    private static boolean causedBy(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    private static String truncate(String s) {
        return s.length() <= 300 ? s : s.substring(0, 300);
    }

    private void pauseBeforeRetry() {
        try {
            pause.sleep(settings.retryBackoff());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelUnavailableException("Retentativa ao Bedrock interrompida", e);
        }
    }

    // ------------------------------------------------------------------ métricas

    private void record(String model, ModelTier tier, String outcome, String error, Duration latency) {
        meters.counter(metricPrefix + ".calls", "model", model, "tier", tier.name(), "outcome", outcome, "error", error)
                .increment();
        Timer.builder(metricPrefix + ".latency").tag("model", model).tag("tier", tier.name())
                .publishPercentiles(0.5, 0.95, 0.99).register(meters).record(latency);
        meters.counter("integration.calls", "provider", PROVIDER, "operation", "converse", "outcome",
                integrationOutcome(outcome)).increment();
        meters.timer("integration.calls.duration", "provider", PROVIDER, "operation", "converse").record(latency);
    }

    private static String integrationOutcome(String outcome) {
        return switch (outcome) {
            case "ok" -> "ok";
            case "MODEL_TIMEOUT" -> "timeout";
            case "MODEL_UNAVAILABLE" -> "transient";
            default -> "rejected";
        };
    }

    private void tokens(String model, ModelTier tier, String type, long amount) {
        if (amount > 0) {
            meters.counter(metricPrefix + ".tokens", "model", model, "tier", tier.name(), "type",
                    type.toLowerCase(Locale.ROOT)).increment(amount);
        }
    }
}
