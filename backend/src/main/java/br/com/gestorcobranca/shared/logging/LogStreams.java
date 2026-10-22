package br.com.gestorcobranca.shared.logging;

/**
 * Nomes dos loggers dedicados. Cada um tem appender próprio em {@code logback-spring.xml}
 * (com {@code additivity=false}), de modo que logs de negócio e de acesso nunca se misturam
 * com logs técnicos.
 */
public final class LogStreams {

    /** Eventos de negócio — insumo de inteligência operacional. Use {@code BusinessEventLogger}. */
    public static final String BUSINESS = "BUSINESS";

    /** Eventos de acesso e segurança — rastreabilidade. Use {@code AccessLogger}. */
    public static final String ACCESS = "ACCESS";

    private LogStreams() {
    }
}
