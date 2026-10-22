package br.com.gestorcobranca.shared.logging.business;

import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.shared.logging.LogStreams;
import br.com.gestorcobranca.shared.logging.MdcKeys;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Implementação de {@link BusinessEventLogger}: persiste em {@code shared.business_event} na
 * transação corrente e publica no logger {@code BUSINESS} após o commit. Também incrementa o
 * contador {@code business.events} (tag {@code type}) para alarmes e painéis no CloudWatch.
 */
@Component
class JdbcBusinessEventLogger implements BusinessEventLogger {

    private static final Logger BUSINESS_LOG = LoggerFactory.getLogger(LogStreams.BUSINESS);

    private static final String INSERT = """
            insert into shared.business_event
              (id, tenant_id, occurred_at, event_type, actor_type, actor_id, session_id, request_id,
               creditor_id, portfolio_id, debtor_id, case_id, reference_type, reference_id,
               channel, amount, duration_ms, attributes)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, cast(? as jsonb))
            """;

    private final JdbcTemplate jdbcTemplate;
    private final CurrentActor currentActor;
    private final JsonCodec json;
    private final Clock clock;
    private final MeterRegistry meterRegistry;

    JdbcBusinessEventLogger(JdbcTemplate jdbcTemplate, CurrentActor currentActor, JsonCodec json,
                            Clock clock, MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentActor = currentActor;
        this.json = json;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void log(BusinessEvent event) {
        Actor actor = currentActor.get();
        UUID id = Ids.newId();
        Instant now = Instant.now(clock);
        String requestId = MDC.get(MdcKeys.REQUEST_ID);
        String attributes = json.toJson(event.attributes());

        jdbcTemplate.update(INSERT,
                id, actor.tenantId(), Timestamp.from(now), event.type(), actor.type().name(), actor.id(),
                actor.sessionId(), requestId, event.creditorId(), event.portfolioId(), event.debtorId(),
                event.caseId(), event.referenceType(), event.referenceId(), event.channel(), event.amount(),
                event.durationMs(), attributes);

        Runnable emit = () -> emit(id, now, actor, requestId, event);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    emit.run();
                }
            });
        } else {
            emit.run();
        }
    }

    private void emit(UUID id, Instant occurredAt, Actor actor, String requestId, BusinessEvent event) {
        meterRegistry.counter("business.events", "type", event.type()).increment();
        LoggingEventBuilder builder = BUSINESS_LOG.atInfo()
                .addKeyValue("eventId", id.toString())
                .addKeyValue("eventType", event.type())
                .addKeyValue("occurredAt", occurredAt.toString())
                .addKeyValue("tenantId", actor.tenantId().toString())
                .addKeyValue("actorType", actor.type().name())
                .addKeyValue("actorId", actor.id().toString());
        addIfPresent(builder, "requestId", requestId);
        addIfPresent(builder, "creditorId", event.creditorId());
        addIfPresent(builder, "portfolioId", event.portfolioId());
        addIfPresent(builder, "debtorId", event.debtorId());
        addIfPresent(builder, "caseId", event.caseId());
        addIfPresent(builder, "referenceType", event.referenceType());
        addIfPresent(builder, "referenceId", event.referenceId());
        addIfPresent(builder, "channel", event.channel());
        addIfPresent(builder, "amount", event.amount());
        addIfPresent(builder, "durationMs", event.durationMs());
        event.attributes().forEach((key, value) -> builder.addKeyValue("attr." + key, value));
        builder.log(event.type());
    }

    private static void addIfPresent(LoggingEventBuilder builder, String key, Object value) {
        if (value != null) {
            builder.addKeyValue(key, value instanceof UUID ? value.toString() : value);
        }
    }
}
