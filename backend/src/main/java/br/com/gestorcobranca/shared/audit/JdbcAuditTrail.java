package br.com.gestorcobranca.shared.audit;

import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.shared.logging.MdcKeys;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.web.ClientAddresses;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Grava a auditoria na transação corrente ({@code MANDATORY}: auditar fora de transação é erro
 * de programação). O {@code content_hash} é calculado por gatilho no banco sobre a forma
 * canônica dos dados ({@code shared.audit_content_hash}).
 */
@Component
class JdbcAuditTrail implements AuditTrail {

    private static final String INSERT = """
            insert into shared.audit_event
              (id, tenant_id, occurred_at, actor_type, actor_id, actor_name, session_id, request_id, client_ip,
               action, entity_type, entity_id, creditor_id, reason, before_state, after_state, metadata)
            values (?, ?, ?, ?, ?, ?, ?, ?, cast(? as inet), ?, ?, ?, ?, ?, cast(? as jsonb), cast(? as jsonb),
                    cast(? as jsonb))
            """;

    private final JdbcTemplate jdbcTemplate;
    private final CurrentActor currentActor;
    private final JsonCodec json;
    private final Clock clock;

    JdbcAuditTrail(JdbcTemplate jdbcTemplate, CurrentActor currentActor, JsonCodec json, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentActor = currentActor;
        this.json = json;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(AuditEntry entry) {
        Actor actor = currentActor.get();
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        jdbcTemplate.update(INSERT,
                Ids.newId(), actor.tenantId(), Timestamp.from(now), actor.type().name(), actor.id(), actor.name(),
                actor.sessionId(), MDC.get(MdcKeys.REQUEST_ID), ClientAddresses.orNull(MDC.get(MdcKeys.CLIENT_IP)),
                entry.action(), entry.entityType(), entry.entityId(), entry.creditorId(), entry.reason(),
                json.toJson(entry.before()), json.toJson(entry.after()), json.toJson(entry.metadata()));
    }
}
