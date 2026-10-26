package br.com.gestorcobranca.helpdesk.infrastructure.provider.bedrock;

import br.com.gestorcobranca.helpdesk.application.port.out.HelpdeskModelPort;
import br.com.gestorcobranca.shared.ai.LanguageModel.ModelMessage;
import br.com.gestorcobranca.shared.ai.LanguageModel.ModelRequest;
import br.com.gestorcobranca.shared.ai.LanguageModel.ModelResponse;
import br.com.gestorcobranca.shared.ai.ModelTier;
import br.com.gestorcobranca.shared.ai.bedrock.BedrockModelSettings;
import br.com.gestorcobranca.shared.ai.bedrock.ConverseTranslator;
import br.com.gestorcobranca.shared.json.JsonCodec;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseOutput;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;
import software.amazon.awssdk.services.bedrockruntime.model.TokenUsage;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Bedrock do chat de suporte: desligado não cria nada; a guarda de transferência internacional e a validação do
 * kernel impedem a subida com configuração inválida; o {@code application.yml} é válido (faltando ligar e aprovar);
 * o adaptador usa o modelo do chat, o nível padrão e as métricas {@code helpdesk.bedrock.*}. Nenhuma chamada à AWS.
 */
class HelpdeskBedrockConfigurationTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(HelpdeskBedrockProperties.class)
    static class Support {

        @Bean
        JsonCodec jsonCodec() {
            return new JsonCodec(JsonMapper.builder().build());
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    private static final String[] VALID = {
            "app.helpdesk.bedrock.enabled=true",
            "app.helpdesk.bedrock.region=us-east-1",
            "app.helpdesk.bedrock.international-transfer-approved=true",
            "app.helpdesk.bedrock.model.model-id=us.anthropic.claude-haiku-5-5",
            "app.helpdesk.bedrock.model.pricing.input-per-million=0.10",
            "app.helpdesk.bedrock.model.pricing.output-per-million=0.50",
            "app.helpdesk.bedrock.model.pricing.cache-read-per-million=0.01",
            "app.helpdesk.bedrock.model.pricing.cache-write-per-million=0.125"};

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Support.class, HelpdeskBedrockConfiguration.class);

    @Test
    void disabledByDefault() {
        runner.run(ctx -> assertThat(ctx).hasNotFailed().doesNotHaveBean(HelpdeskModelPort.class));
    }

    @Test
    void validConfigurationCreatesTheAdapterWithoutCallingAws() {
        runner.withPropertyValues(VALID).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(HelpdeskModelPort.class).provider()).isEqualTo("bedrock");
        });
    }

    @Test
    void internationalTransferGuardAndMissingPricesPreventStartup() {
        runner.withPropertyValues(VALID).withPropertyValues("app.helpdesk.bedrock.international-transfer-approved=false")
                .run(ctx -> assertThat(ctx.getStartupFailure()).rootCause()
                        .hasMessageContaining("app.helpdesk.bedrock.international-transfer-approved").hasMessageContaining("LGPD"));
        runner.withPropertyValues("app.helpdesk.bedrock.enabled=true", "app.helpdesk.bedrock.region=us-east-1",
                        "app.helpdesk.bedrock.international-transfer-approved=true",
                        "app.helpdesk.bedrock.model.model-id=us.anthropic.claude-haiku-5-5")
                .run(ctx -> assertThat(ctx.getStartupFailure()).rootCause()
                        .hasMessageContaining("app.helpdesk.bedrock.model.pricing ausente"));
    }

    @Test
    void applicationYamlDefaultsAreValidAndUseHaiku() {
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(Support.class, HelpdeskBedrockConfiguration.class)
                .withPropertyValues("app.helpdesk.bedrock.enabled=true",
                        "app.helpdesk.bedrock.international-transfer-approved=true")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    HelpdeskBedrockProperties p = ctx.getBean(HelpdeskBedrockProperties.class);
                    assertThat(p.model().modelId()).isEqualTo("us.anthropic.claude-haiku-5-5");
                    assertThat(p.region()).isEqualTo("us-east-1");
                    assertThat(p.model().pricing().inputPerMillion()).isEqualByComparingTo("0.10");
                });
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(Support.class, HelpdeskBedrockConfiguration.class)
                .withPropertyValues("app.helpdesk.bedrock.enabled=true")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void adapterUsesTheChatModelAndItsOwnMetrics() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        RecordingClient client = new RecordingClient();
        JsonCodec json = new JsonCodec(JsonMapper.builder().build());
        HelpdeskBedrockProperties properties = new HelpdeskBedrockProperties(true, "us-east-1", true, null, null, null,
                null, null, "USD", null, new BedrockModelSettings("us.anthropic.claude-haiku-5-5", null,
                new BedrockModelSettings.Pricing(new BigDecimal("0.10"), new BigDecimal("0.50"), new BigDecimal("0.01"),
                        new BigDecimal("0.125"), null, null, null, null, null)));
        BedrockHelpdeskModel model = new BedrockHelpdeskModel(client, properties,
                new ConverseTranslator(json, BedrockHelpdeskModel.CONTEXT_HEADER), meters, d -> { });
        ModelResponse response = model.complete(new ModelRequest("PROMPT", "{\"telaAtual\":\"x\"}",
                List.of(ModelMessage.user("como faço?")), List.of(), 800, Duration.ofSeconds(5), Map.of("product",
                "helpdesk"), ModelTier.ADVANCED));
        assertThat(response.text()).isEqualTo("Assim.");
        assertThat(response.model()).isEqualTo("us.anthropic.claude-haiku-5-5");
        ConverseRequest sent = client.requests.getFirst();
        assertThat(sent.modelId()).isEqualTo("us.anthropic.claude-haiku-5-5");
        assertThat(sent.inferenceConfig().maxTokens()).isEqualTo(800);
        assertThat(sent.system().get(2).text()).startsWith(BedrockHelpdeskModel.CONTEXT_HEADER);
        assertThat(meters.counter("helpdesk.bedrock.calls", "model", "us.anthropic.claude-haiku-5-5", "tier", "STANDARD",
                "outcome", "ok", "error", "none").count()).isEqualTo(1);
        assertThat(meters.find("assistant.bedrock.calls").counters()).isEmpty();
    }

    static final class RecordingClient implements BedrockRuntimeClient {
        final List<ConverseRequest> requests = new ArrayList<>();

        @Override
        public ConverseResponse converse(ConverseRequest request) {
            requests.add(request);
            return ConverseResponse.builder().stopReason(StopReason.END_TURN)
                    .output(ConverseOutput.fromMessage(Message.builder().role(ConversationRole.ASSISTANT)
                            .content(ContentBlock.fromText("Assim.")).build()))
                    .usage(TokenUsage.builder().inputTokens(100).outputTokens(5).totalTokens(105).build()).build();
        }

        @Override
        public String serviceName() {
            return "bedrock-runtime";
        }

        @Override
        public void close() {
        }
    }
}
