package br.com.gestorcobranca.shared.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Tags comuns em todas as métricas publicadas no CloudWatch: permitem separar ambientes e
 * comparar instâncias do Auto Scaling Group.
 */
@Configuration
class ObservabilityConfig {

    @Bean
    MeterRegistryCustomizer<MeterRegistry> commonTags(@Value("${spring.application.name}") String application,
                                                      @Value("${app.environment:local}") String environment) {
        return registry -> registry.config().commonTags("application", application, "env", environment);
    }
}
