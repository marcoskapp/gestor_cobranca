package br.com.gestorcobranca.assistant.infrastructure.provider.bedrock;

import br.com.gestorcobranca.assistant.application.port.out.LanguageModelPort.ModelMessage;
import br.com.gestorcobranca.assistant.application.port.out.LanguageModelPort.ModelRejectedException;
import br.com.gestorcobranca.assistant.application.port.out.LanguageModelPort.ModelRequest;
import br.com.gestorcobranca.assistant.application.port.out.LanguageModelPort.ModelResponse;
import br.com.gestorcobranca.assistant.application.port.out.LanguageModelPort.ModelTimeoutException;
import br.com.gestorcobranca.assistant.application.port.out.LanguageModelPort.ModelUnavailableException;
import br.com.gestorcobranca.assistant.application.port.out.LanguageModelPort.StopReason;
import br.com.gestorcobranca.assistant.application.port.out.LanguageModelPort.ToolCall;
import br.com.gestorcobranca.assistant.application.port.out.LanguageModelPort.ToolDefinition;
import br.com.gestorcobranca.assistant.domain.model.ModelTier;
import br.com.gestorcobranca.shared.json.JsonCodec;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.core.exception.ApiCallTimeoutException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.AccessDeniedException;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.InternalServerException;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.ModelNotReadyException;
import software.amazon.awssdk.services.bedrockruntime.model.ResourceNotFoundException;
import software.amazon.awssdk.services.bedrockruntime.model.ServiceUnavailableException;
import software.amazon.awssdk.services.bedrockruntime.model.ThrottlingException;
import software.amazon.awssdk.services.bedrockruntime.model.TokenUsage;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultStatus;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ValidationException;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Adaptador do Bedrock com o cliente substituído por um dublê (nenhuma chamada real): tradução dos campos,
 * ferramentas em várias rodadas, cache de prompt, mapeamento de erros e retentativa, custo pela tabela
 * configurada (inclusive o limiar de contexto longo do Haiku).
 */
class BedrockLanguageModelTest {

    static final String HAIKU = "us.anthropic.claude-haiku-5-5";
    static final String SONNET = "us.anthropic.claude-sonnet-5-5";
    private static final String SCHEMA = """
            {"type":"object","properties":{"intent":{"type":"string"},"confidence":{"type":"number"}},"required":["intent"]}""";

    private final JsonCodec json = new JsonCodec(JsonMapper.builder().build());
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final FakeClient client = new FakeClient();
    private final List<Duration> pauses = new ArrayList<>();

    static BedrockProperties.Pricing haikuPricing() {
        return new BedrockProperties.Pricing(new BigDecimal("0.10"), new BigDecimal("0.50"), new BigDecimal("0.01"),
                new BigDecimal("0.125"), 100_000L, new BigDecimal("0.50"), new BigDecimal("2.50"), new BigDecimal("0.05"),
                new BigDecimal("0.625"));
    }

    static BedrockProperties.Pricing sonnetPricing() {
        return new BedrockProperties.Pricing(new BigDecimal("2"), new BigDecimal("10"), new BigDecimal("0.10"),
                new BigDecimal("2.50"), null, null, null, null, null);
    }

    static BedrockProperties properties(Boolean advancedCaching, Duration backoff) {
        return new BedrockProperties(true, "us-east-1", true, null, null, null, null, backoff, "usd", null,
                new BedrockProperties.Tier(HAIKU, null, haikuPricing()),
                new BedrockProperties.Tier(SONNET, advancedCaching, sonnetPricing()));
    }

    private BedrockLanguageModel model(BedrockProperties properties) {
        return new BedrockLanguageModel(client, properties, new ConverseTranslator(json), meters, pauses::add);
    }

    private BedrockLanguageModel model() {
        return model(properties(null, Duration.ofMillis(10)));
    }

    private static ModelRequest request(List<ModelMessage> messages, ModelTier tier) {
        return new ModelRequest("PROMPT DO ASSISTENTE", "{\"identity\":\"VERIFIED\",\"offers\":[]}", messages,
                List.of(new ToolDefinition("register_intent", "Registra a intenção", SCHEMA),
                        new ToolDefinition("get_debt_summary", "Saldo", "{\"type\":\"object\",\"properties\":{}}")),
                600, Duration.ofSeconds(20), Map.of("turn", "6f1c2d4e-0000-7000-8000-000000000001",
                "tenant", "6f1c2d4e-0000-7000-8000-000000000002", "nome", "José da Silva — çã"), tier);
    }

    private static ConverseResponse text(String text, TokenUsage usage) {
        return ConverseResponse.builder().stopReason("end_turn").usage(usage)
                .output(ConverseOutput.fromMessage(Message.builder().role(ConversationRole.ASSISTANT)
                        .content(ContentBlock.fromText(text)).build())).build();
    }

    private static TokenUsage usage(int in, int out, Integer cacheRead, Integer cacheWrite) {
        return TokenUsage.builder().inputTokens(in).outputTokens(out).cacheReadInputTokens(cacheRead)
                .cacheWriteInputTokens(cacheWrite).totalTokens(in + out).build();
    }

    private static <T extends AwsServiceException.Builder> AwsServiceException error(Supplier<T> builder, int status,
                                                                                    String code) {
        T b = builder.get();
        b.statusCode(status);
        b.message(code);
        b.requestId("req-1");
        b.awsErrorDetails(AwsErrorDetails.builder().errorCode(code).errorMessage("detalhe " + code).build());
        return b.build();
    }

    // ------------------------------------------------------------------ tradução

    @Test
    void translatesSystemContextToolsLimitsAndMetadata() {
        client.respond(text("Olá! Posso ajudar com o saldo.", usage(1200, 40, 0, 2500)));
        ModelResponse r = model().complete(request(List.of(
                ModelMessage.assistant("Sou a assistente virtual.", List.of()),   // antes do devedor: omitida
                ModelMessage.user("oi"),
                ModelMessage.user("quero saber o saldo")), ModelTier.STANDARD));

        ConverseRequest sent = client.requests.getFirst();
        assertThat(sent.modelId()).isEqualTo(HAIKU);
        assertThat(r.model()).isEqualTo(HAIKU);
        // prompt e contexto em blocos de sistema separados, com o ponto de cache entre eles
        assertThat(sent.system()).hasSize(3);
        assertThat(sent.system().get(0).text()).isEqualTo("PROMPT DO ASSISTENTE");
        assertThat(sent.system().get(1).cachePoint()).isNotNull();
        assertThat(sent.system().get(2).text()).startsWith(ConverseTranslator.CONTEXT_HEADER)
                .endsWith("{\"identity\":\"VERIFIED\",\"offers\":[]}");
        // o contexto nunca vai nas mensagens; mensagens seguidas do devedor unidas; começa no devedor
        assertThat(sent.messages()).singleElement().satisfies(m -> {
            assertThat(m.role()).isEqualTo(ConversationRole.USER);
            assertThat(m.content()).extracting(ContentBlock::text).containsExactly("oi", "quero saber o saldo");
        });
        assertThat(sent.messages().toString()).doesNotContain("VERIFIED");
        // ferramentas com JSON Schema e ponto de cache depois da última
        assertThat(sent.toolConfig().tools()).hasSize(3);
        assertThat(sent.toolConfig().tools().get(0).toolSpec().name()).isEqualTo("register_intent");
        Map<String, Document> schema = sent.toolConfig().tools().get(0).toolSpec().inputSchema().json().asMap();
        assertThat(schema.get("type").asString()).isEqualTo("object");
        assertThat(schema.get("required").asList()).extracting(Document::asString).containsExactly("intent");
        assertThat(sent.toolConfig().tools().get(2).cachePoint()).isNotNull();
        assertThat(sent.inferenceConfig().maxTokens()).isEqualTo(600);
        // metadados: só ids opacos (o valor fora do padrão aceito pelo Bedrock é descartado)
        assertThat(sent.requestMetadata()).containsOnlyKeys("turn", "tenant");
        assertThat(sent.overrideConfiguration()).hasValueSatisfying(o -> {
            assertThat(o.apiCallTimeout()).hasValueSatisfying(t -> assertThat(t).isLessThanOrEqualTo(Duration.ofSeconds(20)));
            assertThat(o.apiCallAttemptTimeout()).isPresent();
        });
        assertThat(r.text()).isEqualTo("Olá! Posso ajudar com o saldo.");
        assertThat(r.stopReason()).isEqualTo(StopReason.END_TURN);
        assertThat(r.usage().cacheWriteInputTokens()).isEqualTo(2500);
        assertThat(meters.counter("assistant.bedrock.calls", "model", HAIKU, "tier", "STANDARD", "outcome", "ok",
                "error", "none").count()).isEqualTo(1);
        assertThat(meters.counter("integration.calls", "provider", "bedrock", "operation", "converse", "outcome", "ok")
                .count()).isEqualTo(1);
    }

    @Test
    void advancedTierUsesTheAdvancedModelAndCachingCanBeTurnedOff() {
        client.respond(text("Estas são as opções.", usage(3000, 80, null, null)));
        ModelResponse r = model(properties(false, Duration.ofMillis(10)))
                .complete(request(List.of(ModelMessage.user("quero parcelar")), ModelTier.ADVANCED));
        ConverseRequest sent = client.requests.getFirst();
        assertThat(sent.modelId()).isEqualTo(SONNET);
        assertThat(r.model()).isEqualTo(SONNET);
        assertThat(sent.system()).hasSize(2).noneMatch(b -> b.cachePoint() != null);
        assertThat(sent.toolConfig().tools()).hasSize(2).noneMatch(t -> t.cachePoint() != null);
        assertThat(meters.counter("assistant.bedrock.tokens", "model", SONNET, "tier", "ADVANCED", "type", "input")
                .count()).isEqualTo(3000);
    }

    @Test
    void toolUseAcrossSeveralRoundsKeepsTheProviderIdsAndResults() {
        client.respond(ConverseResponse.builder().stopReason("tool_use").usage(usage(900, 30, 0, 0))
                .output(ConverseOutput.fromMessage(Message.builder().role(ConversationRole.ASSISTANT).content(
                        ContentBlock.fromText("Vou registrar."),
                        ContentBlock.fromToolUse(ToolUseBlock.builder().toolUseId("tooluse_A1").name("register_intent")
                                .input(Document.mapBuilder().putString("intent", "BALANCE").putNumber("confidence", 0.9)
                                        .build()).build()),
                        ContentBlock.fromToolUse(ToolUseBlock.builder().toolUseId("tooluse_B2").name("get_debt_summary")
                                .input(Document.mapBuilder().build()).build())).build())).build());
        BedrockLanguageModel model = model();
        ModelResponse first = model.complete(request(List.of(ModelMessage.user("quanto devo?")), ModelTier.STANDARD));

        assertThat(first.stopReason()).isEqualTo(StopReason.TOOL_USE);
        assertThat(first.text()).isEqualTo("Vou registrar.");
        assertThat(first.toolCalls()).extracting(ToolCall::id, ToolCall::name)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("tooluse_A1", "register_intent"),
                        org.assertj.core.groups.Tuple.tuple("tooluse_B2", "get_debt_summary"));
        assertThat(json.toMap(first.toolCalls().getFirst().argumentsJson())).containsEntry("intent", "BALANCE");

        // segunda rodada: a conversa volta com as chamadas e os resultados (um deles com erro)
        client.respond(text("O saldo atualizado é R$ 800,00.", usage(1100, 20, 900, 0)));
        List<ModelMessage> conversation = new ArrayList<>(List.of(ModelMessage.user("quanto devo?")));
        conversation.add(ModelMessage.assistant(first.text(), first.toolCalls()));
        conversation.add(ModelMessage.tool("tooluse_A1", "{\"recorded\":true,\"understood\":true}"));
        conversation.add(ModelMessage.tool("tooluse_B2", "{\"error\":\"debt_unavailable\"}"));
        ModelResponse second = model.complete(request(conversation, ModelTier.STANDARD));

        List<Message> sent = client.requests.get(1).messages();
        assertThat(sent).extracting(Message::role).containsExactly(ConversationRole.USER, ConversationRole.ASSISTANT,
                ConversationRole.USER);
        assertThat(sent.get(1).content()).hasSize(3);
        assertThat(sent.get(1).content().get(1).toolUse().toolUseId()).isEqualTo("tooluse_A1");
        assertThat(sent.get(1).content().get(1).toolUse().input().asMap().get("intent").asString()).isEqualTo("BALANCE");
        assertThat(sent.get(2).content()).extracting(b -> b.toolResult().toolUseId())
                .containsExactly("tooluse_A1", "tooluse_B2");
        assertThat(sent.get(2).content().get(0).toolResult().status()).isNull();
        assertThat(sent.get(2).content().get(1).toolResult().status()).isEqualTo(ToolResultStatus.ERROR);
        assertThat(sent.get(2).content().get(0).toolResult().content().getFirst().json().asMap().get("recorded")
                .asBoolean()).isTrue();
        assertThat(second.stopReason()).isEqualTo(StopReason.END_TURN);
        assertThat(second.usage().cacheReadInputTokens()).isEqualTo(900);
        assertThat(second.usage().promptTokens()).isEqualTo(2000);
    }

    @Test
    void assistantTextAfterTheLastDebtorMessageIsNotSentAsPrefill() {
        client.respond(text("ok", usage(10, 1, 0, 0)));
        model().complete(request(List.of(ModelMessage.user("pode ser amanhã?"),
                ModelMessage.assistant("Um atendente respondeu antes do turno.", List.of())), ModelTier.STANDARD));
        assertThat(client.requests.getFirst().messages()).extracting(Message::role).containsExactly(ConversationRole.USER);
    }

    // ------------------------------------------------------------------ custo

    @Test
    void costUsesTheConfiguredTableIncludingCacheTokens() {
        client.respond(text("ok", usage(1000, 200, 3000, 0)));
        ModelResponse r = model().complete(request(List.of(ModelMessage.user("oi")), ModelTier.STANDARD));
        // (1000 × 0,10 + 200 × 0,50 + 3000 × 0,01) / 1.000.000
        assertThat(r.cost().amount()).isEqualByComparingTo("0.000230");
        assertThat(r.cost().currency()).isEqualTo("USD");
        assertThat(r.usage().inputTokens()).isEqualTo(1000);
        assertThat(r.usage().cacheReadInputTokens()).isEqualTo(3000);
        assertThat(meters.counter("assistant.bedrock.tokens", "model", HAIKU, "tier", "STANDARD", "type", "cache_read")
                .count()).isEqualTo(3000);
        assertThat(meters.counter("assistant.bedrock.cost", "model", HAIKU, "tier", "STANDARD", "currency", "USD").count())
                .isEqualTo(0.00023, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void haikuLongContextPricingStartsAboveOneHundredThousandPromptTokens() {
        BedrockProperties.Pricing haiku = haikuPricing();
        // exatamente no limiar: faixa padrão
        assertThat(BedrockPricing.cost(haiku, new br.com.gestorcobranca.assistant.application.port.out.LanguageModelPort
                .Usage(100_000, 0, 0, 0))).isEqualByComparingTo("0.010000");
        // 95 mil sem cache + 10 mil lidos do cache = 105 mil de prompt: todos os itens na faixa longa
        var longUsage = new br.com.gestorcobranca.assistant.application.port.out.LanguageModelPort.Usage(95_000, 1_000,
                10_000, 0);
        assertThat(BedrockPricing.longContext(haiku, longUsage)).isTrue();
        assertThat(BedrockPricing.cost(haiku, longUsage)).isEqualByComparingTo("0.050500");
        // gravação de cache conta para o limiar e usa o preço longo de gravação
        assertThat(BedrockPricing.cost(haiku, new br.com.gestorcobranca.assistant.application.port.out.LanguageModelPort
                .Usage(1_000, 0, 0, 100_000))).isEqualByComparingTo("0.063000");
        // Sonnet sem limiar configurado: preço único
        assertThat(BedrockPricing.cost(sonnetPricing(), new br.com.gestorcobranca.assistant.application.port.out
                .LanguageModelPort.Usage(200_000, 1_000, 0, 0))).isEqualByComparingTo("0.410000");

        client.respond(text("ok", usage(95_000, 1_000, 10_000, 0)));
        ModelResponse r = model().complete(request(List.of(ModelMessage.user("oi")), ModelTier.STANDARD));
        assertThat(r.cost().amount()).isEqualByComparingTo("0.050500");
        assertThat(meters.counter("assistant.bedrock.long_context", "model", HAIKU).count()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ motivo de parada

    @Test
    void providerContentFilterAndGuardrailBecomeContentFiltered() {
        for (String reason : List.of("content_filtered", "guardrail_intervened")) {
            client.respond(ConverseResponse.builder().stopReason(reason).usage(usage(10, 0, 0, 0))
                    .output(ConverseOutput.fromMessage(Message.builder().role(ConversationRole.ASSISTANT)
                            .content(List.of()).build())).build());
            assertThat(model().complete(request(List.of(ModelMessage.user("oi")), ModelTier.STANDARD)).stopReason())
                    .isEqualTo(StopReason.CONTENT_FILTERED);
        }
        client.respond(ConverseResponse.builder().stopReason("max_tokens").usage(usage(10, 600, 0, 0))
                .output(ConverseOutput.fromMessage(Message.builder().role(ConversationRole.ASSISTANT)
                        .content(ContentBlock.fromText("texto cortado")).build())).build());
        assertThat(model().complete(request(List.of(ModelMessage.user("oi")), ModelTier.STANDARD)).stopReason())
                .isEqualTo(StopReason.MAX_TOKENS);
    }

    @Test
    void contextWindowAndMalformedOutputAreFailures() {
        client.respond(ConverseResponse.builder().stopReason("model_context_window_exceeded").usage(usage(10, 0, 0, 0))
                .output(ConverseOutput.fromMessage(Message.builder().role(ConversationRole.ASSISTANT).content(List.of())
                        .build())).build());
        assertThatThrownBy(() -> model().complete(request(List.of(ModelMessage.user("oi")), ModelTier.STANDARD)))
                .isInstanceOf(ModelRejectedException.class);
        client.respond(ConverseResponse.builder().stopReason("malformed_tool_use").usage(usage(10, 0, 0, 0))
                .output(ConverseOutput.fromMessage(Message.builder().role(ConversationRole.ASSISTANT).content(List.of())
                        .build())).build());
        assertThatThrownBy(() -> model().complete(request(List.of(ModelMessage.user("oi")), ModelTier.STANDARD)))
                .isInstanceOf(ModelUnavailableException.class);
        assertThat(meters.counter("assistant.bedrock.calls", "model", HAIKU, "tier", "STANDARD", "outcome",
                "MODEL_REJECTED", "error", "model_context_window_exceeded").count()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ erros e retentativa

    @Test
    void throttlingIsRetriedOnceAndThenBecomesUnavailable() {
        client.fail(error(ThrottlingException::builder, 429, "ThrottlingException"));
        client.respond(text("ok", usage(10, 1, 0, 0)));
        assertThat(model().complete(request(List.of(ModelMessage.user("oi")), ModelTier.STANDARD)).text()).isEqualTo("ok");
        assertThat(client.requests).hasSize(2);
        assertThat(pauses).containsExactly(Duration.ofMillis(10));
        assertThat(meters.counter("assistant.bedrock.retries", "model", HAIKU, "reason", "ThrottlingException").count())
                .isEqualTo(1);

        client.requests.clear();
        client.fail(error(ThrottlingException::builder, 429, "ThrottlingException"));
        client.fail(error(ThrottlingException::builder, 429, "ThrottlingException"));
        assertThatThrownBy(() -> model().complete(request(List.of(ModelMessage.user("oi")), ModelTier.STANDARD)))
                .isInstanceOf(ModelUnavailableException.class);
        assertThat(client.requests).hasSize(2);
        assertThat(meters.counter("integration.calls", "provider", "bedrock", "operation", "converse", "outcome",
                "transient").count()).isEqualTo(1);
    }

    @Test
    void serverErrorsAndModelNotReadyAreRetriedOnce() {
        for (AwsServiceException e : List.of(error(InternalServerException::builder, 500, "InternalServerException"),
                error(ServiceUnavailableException::builder, 503, "ServiceUnavailableException"),
                error(ModelNotReadyException::builder, 429, "ModelNotReadyException"),
                error(AwsServiceException::builder, 502, "BadGateway"))) {
            client.requests.clear();
            client.fail(e);
            client.fail(e);
            assertThatThrownBy(() -> model().complete(request(List.of(ModelMessage.user("oi")), ModelTier.ADVANCED)))
                    .isInstanceOf(ModelUnavailableException.class);
            assertThat(client.requests).as(e.awsErrorDetails().errorCode()).hasSize(2);
        }
    }

    @Test
    void definitiveRefusalsAreRejectedWithoutRetry() {
        for (AwsServiceException e : List.of(error(ValidationException::builder, 400, "ValidationException"),
                error(AccessDeniedException::builder, 403, "AccessDeniedException"),
                error(ResourceNotFoundException::builder, 404, "ResourceNotFoundException"),
                error(AwsServiceException::builder, 400, "UnrecognizedClientException"))) {
            client.requests.clear();
            client.fail(e);
            assertThatThrownBy(() -> model().complete(request(List.of(ModelMessage.user("oi")), ModelTier.STANDARD)))
                    .as(e.awsErrorDetails().errorCode()).isInstanceOf(ModelRejectedException.class);
            assertThat(client.requests).hasSize(1);
        }
        assertThat(pauses).isEmpty();
    }

    @Test
    void timeoutsAreNotRetried() {
        client.fail(error(software.amazon.awssdk.services.bedrockruntime.model.ModelTimeoutException::builder, 408,
                "ModelTimeoutException"));
        assertThatThrownBy(() -> model().complete(request(List.of(ModelMessage.user("oi")), ModelTier.STANDARD)))
                .isInstanceOf(ModelTimeoutException.class);
        client.fail(ApiCallTimeoutException.create(20_000));
        assertThatThrownBy(() -> model().complete(request(List.of(ModelMessage.user("oi")), ModelTier.STANDARD)))
                .isInstanceOf(ModelTimeoutException.class);
        client.fail(SdkClientException.builder().message("read timed out")
                .cause(new java.net.SocketTimeoutException("Read timed out")).build());
        assertThatThrownBy(() -> model().complete(request(List.of(ModelMessage.user("oi")), ModelTier.STANDARD)))
                .isInstanceOf(ModelTimeoutException.class);
        assertThat(client.requests).hasSize(3);
        assertThat(meters.counter("integration.calls", "provider", "bedrock", "operation", "converse", "outcome",
                "timeout").count()).isEqualTo(3);
    }

    @Test
    void networkFailureIsUnavailableAndRetryNeedsTimeLeft() {
        client.fail(SdkClientException.builder().message("Unable to execute HTTP request")
                .cause(new java.net.ConnectException("refused")).build());
        assertThatThrownBy(() -> model().complete(request(List.of(ModelMessage.user("oi")), ModelTier.STANDARD)))
                .isInstanceOf(ModelUnavailableException.class);
        assertThat(client.requests).hasSize(1);

        // espera da retentativa maior que o prazo restante: não repete
        client.requests.clear();
        client.fail(error(ThrottlingException::builder, 429, "ThrottlingException"));
        assertThatThrownBy(() -> model(properties(null, Duration.ofSeconds(30)))
                .complete(request(List.of(ModelMessage.user("oi")), ModelTier.STANDARD)))
                .isInstanceOf(ModelUnavailableException.class);
        assertThat(client.requests).hasSize(1);
    }

    /** Dublê do cliente do Bedrock: respostas ou exceções roteirizadas, pedidos guardados. */
    static final class FakeClient implements BedrockRuntimeClient {

        final List<ConverseRequest> requests = new ArrayList<>();
        private final Deque<Object> script = new ArrayDeque<>();

        void respond(ConverseResponse response) {
            script.add(response);
        }

        void fail(RuntimeException e) {
            script.add(e);
        }

        @Override
        public ConverseResponse converse(ConverseRequest request) {
            requests.add(request);
            Object next = script.poll();
            if (next instanceof RuntimeException e) {
                throw e;
            }
            if (next == null) {
                throw new IllegalStateException("dublê sem resposta roteirizada");
            }
            return (ConverseResponse) next;
        }

        @Override
        public String serviceName() {
            return "bedrock";
        }

        @Override
        public void close() {
        }
    }
}
