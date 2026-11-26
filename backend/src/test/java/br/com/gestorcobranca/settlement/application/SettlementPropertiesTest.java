package br.com.gestorcobranca.settlement.application;

import br.com.gestorcobranca.settlement.domain.service.StatementResponseDeadline;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/** O prazo de resposta do demonstrativo fora de 1 a 90 dias impede a aplicação de subir. */
class SettlementPropertiesTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SettlementProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class);

    @Test
    void defaultIsTenDays() {
        runner.run(ctx -> assertThat(ctx.getBean(SettlementProperties.class).statementResponseDays())
                .isEqualTo(StatementResponseDeadline.DEFAULT_DAYS));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, StatementResponseDeadline.MAX_DAYS})
    void acceptsTheLimits(int days) {
        runner.withPropertyValues("app.settlement.statement-response-days=" + days)
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, StatementResponseDeadline.MAX_DAYS + 1})
    void rejectsOutOfRange(int days) {
        runner.withPropertyValues("app.settlement.statement-response-days=" + days)
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
