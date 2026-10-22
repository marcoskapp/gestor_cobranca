package br.com.gestorcobranca.identity.infrastructure.persistence;

import br.com.gestorcobranca.identity.application.port.in.GovernanceQueries.AccessLogEntry;
import br.com.gestorcobranca.identity.application.port.in.GovernanceQueries.AccessLogFilter;
import br.com.gestorcobranca.identity.application.port.in.GovernanceQueries.AuditFilter;
import br.com.gestorcobranca.identity.application.port.in.GovernanceQueries.AuditLogEntry;
import br.com.gestorcobranca.identity.application.port.out.GovernanceReadRepository;
import br.com.gestorcobranca.shared.json.JsonCodec;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/**
 * Consultas de leitura sobre {@code shared.access_log} e {@code shared.audit_event}. O RLS
 * garante que somente registros do tenant corrente sejam retornados.
 */
@Repository
class JdbcGovernanceReadRepository implements GovernanceReadRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final JsonCodec json;

    JdbcGovernanceReadRepository(NamedParameterJdbcTemplate jdbc, JsonCodec json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public Page<AccessLogEntry> accessLog(AccessLogFilter filter, Pageable pageable) {
        StringBuilder where = new StringBuilder(" where a.occurred_at between :from and :to");
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("from", Timestamp.from(filter.from()))
                .addValue("to", Timestamp.from(filter.to()));
        if (filter.actorId() != null) {
            where.append(" and a.actor_id = :actorId");
            params.addValue("actorId", filter.actorId());
        }
        if (filter.eventType() != null && !filter.eventType().isBlank()) {
            where.append(" and a.event_type = :eventType");
            params.addValue("eventType", filter.eventType());
        }
        if (filter.success() != null) {
            where.append(" and a.success = :success");
            params.addValue("success", filter.success());
        }
        if (filter.clientIp() != null && !filter.clientIp().isBlank()) {
            where.append(" and a.client_ip = cast(:clientIp as inet)");
            params.addValue("clientIp", filter.clientIp());
        }
        Long total = jdbc.queryForObject("select count(*) from shared.access_log a" + where, params, Long.class);
        params.addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());
        List<AccessLogEntry> content = jdbc.query("""
                select a.id, a.occurred_at, a.event_type, a.success, a.actor_type, a.actor_id, u.name as actor_name,
                       a.session_id, a.principal, host(a.client_ip) as client_ip, a.user_agent, a.request_id, a.route,
                       a.details::text as details
                from shared.access_log a
                left join identity.app_user u on u.id = a.actor_id
                """ + where + " order by a.occurred_at desc limit :limit offset :offset", params,
                (rs, i) -> new AccessLogEntry(rs.getObject("id", UUID.class), rs.getTimestamp("occurred_at").toInstant(),
                        rs.getString("event_type"), rs.getBoolean("success"), rs.getString("actor_type"),
                        rs.getObject("actor_id", UUID.class), rs.getString("actor_name"),
                        rs.getObject("session_id", UUID.class), rs.getString("principal"), rs.getString("client_ip"),
                        rs.getString("user_agent"), rs.getString("request_id"), rs.getString("route"),
                        json.toMap(rs.getString("details"))));
        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }

    @Override
    public Page<AuditLogEntry> auditTrail(AuditFilter filter, Pageable pageable) {
        StringBuilder where = new StringBuilder(" where e.occurred_at between :from and :to");
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("from", Timestamp.from(filter.from()))
                .addValue("to", Timestamp.from(filter.to()));
        if (filter.actorId() != null) {
            where.append(" and e.actor_id = :actorId");
            params.addValue("actorId", filter.actorId());
        }
        if (filter.entityType() != null && !filter.entityType().isBlank()) {
            where.append(" and e.entity_type = :entityType");
            params.addValue("entityType", filter.entityType());
        }
        if (filter.entityId() != null) {
            where.append(" and e.entity_id = :entityId");
            params.addValue("entityId", filter.entityId());
        }
        if (filter.action() != null && !filter.action().isBlank()) {
            where.append(" and e.action = :action");
            params.addValue("action", filter.action());
        }
        if (filter.creditorId() != null) {
            where.append(" and e.creditor_id = :creditorId");
            params.addValue("creditorId", filter.creditorId());
        }
        Long total = jdbc.queryForObject("select count(*) from shared.audit_event e" + where, params, Long.class);
        params.addValue("limit", pageable.getPageSize()).addValue("offset", pageable.getOffset());
        List<AuditLogEntry> content = jdbc.query("""
                select e.seq, e.id, e.occurred_at, e.actor_type, e.actor_id, e.actor_name, e.action, e.entity_type,
                       e.entity_id, e.creditor_id, e.reason, e.before_state::text as before_state,
                       e.after_state::text as after_state, e.metadata::text as metadata, e.request_id,
                       host(e.client_ip) as client_ip, e.chain_hash is not null as sealed
                from shared.audit_event e
                """ + where + " order by e.seq desc limit :limit offset :offset", params,
                (rs, i) -> new AuditLogEntry(rs.getLong("seq"), rs.getObject("id", UUID.class),
                        rs.getTimestamp("occurred_at").toInstant(), rs.getString("actor_type"),
                        rs.getObject("actor_id", UUID.class), rs.getString("actor_name"), rs.getString("action"),
                        rs.getString("entity_type"), rs.getObject("entity_id", UUID.class),
                        rs.getObject("creditor_id", UUID.class), rs.getString("reason"),
                        json.toMap(rs.getString("before_state")), json.toMap(rs.getString("after_state")),
                        json.toMap(rs.getString("metadata")), rs.getString("request_id"), rs.getString("client_ip"),
                        rs.getBoolean("sealed")));
        return new PageImpl<>(content, pageable, total == null ? 0 : total);
    }
}
