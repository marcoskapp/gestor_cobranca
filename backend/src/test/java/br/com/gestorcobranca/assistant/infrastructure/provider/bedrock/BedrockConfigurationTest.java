package br.com.gestorcobranca.assistant.infrastructure.provider.bedrock;

import br.com.gestorcobranca.assistant.application.port.out.LanguageModelPort;
import br.com.gestorcobranca.shared.json.JsonCodec;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Subida do adaptador: desligado não cria nada; ligado com configuração inválida impede a aplicação de subir;
 * os valores do {@code application.yml} formam uma configuração válida (faltando só ligar e aprovar a
 * transferência internacional, que o responsável decide). Nenhuma chamada à AWS.
 */
class BedrockConfigurationTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(BedrockProperties.class)
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
            "app.assistant.bedrock.enabled=true",
            "app.assistant.bedrock.region=us-east-1",
            "app.assistant.bedrock.international-transfer-approved=true",
            "app.assistant.bedrock.standard.model-id=us.anthropic.claude-haiku-5-5",
            "app.assistant.bedrock.standard.pricing.input-per-million=0.10",
            "app.assistant.bedrock.standard.pricing.output-per-million=0.50",
            "app.assistant.bedrock.standard.pricing.cache-read-per-million=0.01",
            "app.assistant.bedrock.standard.pricing.cache-write-per-million=0.125",
            "app.assistant.bedrock.advanced.model-id=us.anthropic.claude-sonnet-5-5",
            "app.assistant.bedrock.advanced.pricing.input-per-million=2",
            "app.assistant.bedrock.advanced.pricing.output-per-million=10",
            "app.assistant.bedrock.advanced.pricing.cache-read-per-million=0.10",
            "app.assistant.bedrock.advanced.pricing.cache-write-per-million=2.50"};

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Support.class, BedrockConfiguration.class);

    @Test
    void disabledByDefault() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(LanguageModelPort.class);
        });
    }

    @Test
    void validConfigurationCreatesTheAdapterWithoutCallingAws() {
        runner.withPropertyValues(VALID).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(LanguageModelPort.class).provider()).isEqualTo("bedrock");
        });
    }

    @Test
    void missingPricesOrModelPreventStartup() {
        runner.withPropertyValues("app.assistant.bedrock.enabled=true", "app.assistant.bedrock.region=us-east-1",
                "app.assistant.bedrock.international-transfer-approved=true",
                "app.assistant.bedrock.standard.model-id=us.anthropic.claude-haiku-5-5").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("standard.pricing ausente")
                    .hasMessageContaining("advanced ausente");
        });
        runner.withPropertyValues(VALID).withPropertyValues("app.assistant.bedrock.standard.pricing.long-context-threshold=100000")
                .run(ctx -> assertThat(ctx.getStartupFailure()).rootCause()
                        .hasMessageContaining("long-input-per-million"));
        runner.withPropertyValues(VALID).withPropertyValues("app.assistant.bedrock.region=", "app.assistant.bedrock.cache-ttl=2h",
                        "app.assistant.bedrock.currency=dolar")
                .run(ctx -> assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("region")
                        .hasMessageContaining("cache-ttl").hasMessageContaining("currency"));
    }

    @Test
    void internationalTransferMustBeApprovedExplicitly() {
        runner.withPropertyValues(VALID).withPropertyValues("app.assistant.bedrock.international-transfer-approved=false")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).rootCause().hasMessageContaining("international-transfer-approved")
                            .hasMessageContaining("LGPD");
                });
    }

    @Test
    void applicationYamlDefaultsAreAValidConservativeConfiguration() {
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(Support.class, BedrockConfiguration.class)
                .withPropertyValues("app.assistant.bedrock.enabled=true",
                        "app.assistant.bedrock.international-transfer-approved=true")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    BedrockProperties p = ctx.getBean(BedrockProperties.class);
                    assertThat(p.region()).isEqualTo("us-east-1");
                    assertThat(p.standard().modelId()).isEqualTo("us.anthropic.claude-haiku-5-5");
                    assertThat(p.advanced().modelId()).isEqualTo("us.anthropic.claude-sonnet-5-5");
                    assertThat(p.standard().pricing().longContextThreshold()).isEqualTo(100_000L);
                    assertThat(p.standard().pricing().inputPerMillion()).isEqualByComparingTo(new BigDecimal("0.10"));
                    assertThat(p.advanced().pricing().cacheReadPerMillion()).isEqualByComparingTo(new BigDecimal("0.10"));
                    assertThat(p.currency()).isEqualTo("USD");
                });
        // sem a aprovação explícita, o padrão do application.yml não sobe
        new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(Support.class, BedrockConfiguration.class)
                .withPropertyValues("app.assistant.bedrock.enabled=true")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
