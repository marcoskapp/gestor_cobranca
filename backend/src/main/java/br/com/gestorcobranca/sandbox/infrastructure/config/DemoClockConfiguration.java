package br.com.gestorcobranca.sandbox.infrastructure.config;

import br.com.gestorcobranca.sandbox.infrastructure.time.ShiftableClock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import java.time.Clock;

/**
 * Relógio de demonstração (decisão 1351): no perfil {@code sandbox} com a carga ligada (as mesmas duas condições de
 * {@code @SandboxSeedComponent}; o {@code SandboxSeedGuard} recusa a flag com {@code prod} ou fora do ambiente
 * {@code sandbox}), o {@code Clock} da aplicação ({@code TimeConfig#clock}) é substituído por um relógio deslocável
 * ({@link ShiftableClock}, {@code @Primary}) sobre o relógio UTC do sistema. Sem viagem em curso, ele é idêntico ao
 * relógio real. A carga o desloca para registrar o histórico fictício de 12 meses pelos casos de uso e sempre volta ao
 * presente ao terminar. Em qualquer outro perfil este bean não existe.
 */
@Configuration(proxyBeanMethods = false)
@Profile("sandbox")
@ConditionalOnProperty(prefix = "app.sandbox.seed", name = "enabled", havingValue = "true")
class DemoClockConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(DemoClockConfiguration.class);

    @Bean
    @Primary
    ShiftableClock demoClock() {
        LOG.warn("Relógio de demonstração do sandbox ativo (deslocável pela carga de dados fictícios)");
        return new ShiftableClock(Clock.systemUTC());
    }
}
