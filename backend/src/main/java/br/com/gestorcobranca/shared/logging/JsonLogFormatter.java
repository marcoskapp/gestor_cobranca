package br.com.gestorcobranca.shared.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import org.slf4j.event.KeyValuePair;
import org.springframework.boot.logging.structured.StructuredLogFormatter;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Formato JSON único para os três fluxos de log (técnico, negócio e acesso), uma linha por
 * evento, pronto para o CloudWatch Logs Insights.
 * <p>
 * Campos fixos: {@code @timestamp, level, stream, logger, thread, message, service, env}.
 * Todo o MDC (requestId, tenantId, actorId…) e os pares chave-valor do SLF4J
 * ({@code log.atInfo().addKeyValue(...)}) viram campos de primeiro nível. A mensagem passa por
 * {@link SensitiveDataMasker}; pares chave-valor devem ser emitidos já sem dados pessoais.
 * <p>
 * Registrado em {@code logback-spring.xml} via {@code StructuredLogEncoder}.
 */
public class JsonLogFormatter implements StructuredLogFormatter<ILoggingEvent> {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_STACK_LENGTH = 16_000;

    private final String service = System.getProperty("app.service-name", "gestor-cobranca");
    private final String environment = System.getenv().getOrDefault("APP_ENV", "local");
    private final String instance = System.getenv().getOrDefault("APP_INSTANCE_ID", "local");

    @Override
    public String format(ILoggingEvent event) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("@timestamp", Instant.ofEpochMilli(event.getTimeStamp()).toString());
        json.put("level", event.getLevel().toString());
        json.put("stream", streamOf(event.getLoggerName()));
        json.put("logger", event.getLoggerName());
        json.put("thread", event.getThreadName());
        json.put("service", service);
        json.put("env", environment);
        json.put("instance", instance);
        json.put("message", SensitiveDataMasker.mask(event.getFormattedMessage()));
        Map<String, String> mdc = event.getMDCPropertyMap();
        if (mdc != null) {
            json.putAll(mdc);
        }
        List<KeyValuePair> pairs = event.getKeyValuePairs();
        if (pairs != null) {
            for (KeyValuePair pair : pairs) {
                json.put(pair.key, pair.value);
            }
        }
        IThrowableProxy throwable = event.getThrowableProxy();
        if (throwable != null) {
            json.put("error.type", throwable.getClassName());
            json.put("error.message", SensitiveDataMasker.mask(throwable.getMessage()));
            String stack = ThrowableProxyUtil.asString(throwable);
            json.put("error.stack", SensitiveDataMasker.mask(
                    stack.length() > MAX_STACK_LENGTH ? stack.substring(0, MAX_STACK_LENGTH) : stack));
        }
        return JSON.writeValueAsString(json) + System.lineSeparator();
    }

    private static String streamOf(String loggerName) {
        return switch (loggerName) {
            case LogStreams.BUSINESS -> "business";
            case LogStreams.ACCESS -> "access";
            default -> "technical";
        };
    }
}
