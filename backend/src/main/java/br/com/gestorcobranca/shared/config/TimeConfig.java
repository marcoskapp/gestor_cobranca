package br.com.gestorcobranca.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * Tempo da aplicação: instantes sempre em UTC ({@link Clock}); datas de negócio (vencimentos,
 * janelas de contato) no fuso de operação ({@link #BUSINESS_ZONE}). Injetar {@code Clock} em vez
 * de chamar {@code Instant.now()} torna regras temporais testáveis.
 */
@Configuration
public class TimeConfig {

    /** Identificador do fuso de operação, para anotações que exigem constante (ex.: {@code @Scheduled(zone)}). */
    public static final String BUSINESS_ZONE_ID = "America/Sao_Paulo";

    public static final ZoneId BUSINESS_ZONE = ZoneId.of(BUSINESS_ZONE_ID);

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
