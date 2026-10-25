package br.com.gestorcobranca.shared.logging.access;

import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.shared.logging.LogStreams;
import br.com.gestorcobranca.shared.logging.MdcKeys;
import br.com.gestorcobranca.shared.web.ClientAddresses;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Persiste o evento em transação independente ({@code REQUIRES_NEW}) e o emite no logger
 * {@code ACCESS}. Falha na gravação nunca é silenciosa: é registrada no log técnico em ERROR e
 * contabilizada em {@code access.log.write_failures} (alarme no CloudWatch), mas não
 * interrompe a operação de negócio.
 */
@Component
class JdbcAccessLogger implements AccessLogger {

    private static final Logger ACCESS_LOG = LoggerFactory.getLogger(LogStreams.ACCESS);
    private static final Logger LOG = LoggerFactory.getLogger(JdbcAccessLogger.class);

    private static final String INSERT = """
            insert into shared.access_log
              (id, tenant_id, occurred_at, event_type, success, actor_type, actor_id, session_id,
               principal, client_ip, user_agent, request_id, route, details)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as inet), ?, ?, ?, cast(? as jsonb))
            """;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate requiresNew;
    private final JsonCodec json;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    JdbcAccessLogger(JdbcTemplate jdbcTemplate, PlatformTransactionManager transactionManager, JsonCodec json,
                     Clock clock, MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.json = json;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void log(AccessEvent incoming) {
        AccessEvent event = withIpValidity(incoming);
        UUID id = Ids.newId();
        Instant now = Instant.now(clock);
        // nunca grava em inet um valor que não seja IP literal: o evento sai sem IP, mas sai
        String clientIp = ClientAddresses.orNull(MDC.get(MdcKeys.CLIENT_IP));
        String userAgent = truncate(MDC.get(MdcKeys.USER_AGENT), 512);
        String requestId = MDC.get(MdcKeys.REQUEST_ID);
        String route = MDC.get(MdcKeys.ROUTE);

        meterRegistry.counter("access.events", "type", event.type().name(), "success", String.valueOf(event.success())).increment();
        emit(id, now, event, clientIp, userAgent, requestId, route);
        try {
            requiresNew.executeWithoutResult(status -> jdbcTemplate.update(INSERT,
                    id, event.tenantId(), Timestamp.from(now), event.type().name(), event.success(),
                    event.actorType(), event.actorId(), event.sessionId(), event.principal(),
                    clientIp, userAgent, requestId, route, json.toJson(event.details())));
        } catch (RuntimeException e) {
            meterRegistry.counter("access.log.write_failures").increment();
            LOG.error("Falha ao persistir evento de acesso {} ({})", id, event.type(), e);
        }
    }

    /** IP de cliente inválido informado pelo proxy: o evento é gravado sem IP e marcado. */
    private static AccessEvent withIpValidity(AccessEvent event) {
        String rawIp = MDC.get(MdcKeys.CLIENT_IP);
        boolean invalid = (rawIp != null && !ClientAddresses.isValid(rawIp))
                || "true".equals(MDC.get(MdcKeys.CLIENT_IP_INVALID));
        if (!invalid) {
            return event;
        }
        Map<String, String> details = new LinkedHashMap<>(event.details());
        details.put("clientIpInvalid", "true");
        return event.withDetails(details);
    }

    private static void emit(UUID id, Instant now, AccessEvent event, String clientIp, String userAgent,
                             String requestId, String route) {
        LoggingEventBuilder builder = ACCESS_LOG.atLevel(event.success() ? Level.INFO : Level.WARN)
                .addKeyValue("eventId", id.toString())
                .addKeyValue("eventType", event.type().name())
                .addKeyValue("occurredAt", now.toString())
                .addKeyValue("success", event.success())
                .addKeyValue("actorType", event.actorType());
        if (event.tenantId() != null) {
            builder.addKeyValue("tenantId", event.tenantId().toString());
        }
        if (event.actorId() != null) {
            builder.addKeyValue("actorId", event.actorId().toString());
        }
        if (event.sessionId() != null) {
            builder.addKeyValue("sessionId", event.sessionId().toString());
        }
        if (event.principal() != null) {
            builder.addKeyValue("principal", event.principal());
        }
        if (clientIp != null) {
            builder.addKeyValue("clientIp", clientIp);
        }
        if (userAgent != null) {
            builder.addKeyValue("userAgent", userAgent);
        }
        if (requestId != null) {
            builder.addKeyValue("requestId", requestId);
        }
        if (route != null) {
            builder.addKeyValue("route", route);
        }
        event.details().forEach((k, v) -> builder.addKeyValue("detail." + k, v));
        builder.log(event.type().name());
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
