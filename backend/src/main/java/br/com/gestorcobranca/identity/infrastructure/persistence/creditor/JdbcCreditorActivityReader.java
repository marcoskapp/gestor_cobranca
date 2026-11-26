package br.com.gestorcobranca.identity.infrastructure.persistence.creditor;

import br.com.gestorcobranca.identity.application.port.out.CreditorActivityReader;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Atividade das pessoas de um credor em {@code shared.audit_event} (só o ator {@code CREDITOR}) e
 * {@code shared.access_log}, sob o RLS da assessoria. As pessoas vêm de {@code identity.creditor_user} filtrado por
 * {@code creditor_id = :creditorId} (incluídas as revogadas); a coluna {@code creditor_id} da auditoria nunca é usada
 * (ela também marca ações da assessoria sobre o credor). Lê só código, referência, instante e id — nunca antes/depois,
 * metadados, motivo, nome do ator, IP ou sessão. Índices {@code ix_audit_event_actor} e {@code ix_access_log_actor}
 * ({@code tenant_id, actor_id, occurred_at desc}); o cursor vai como comparação de linha {@code (occurred_at, id) <
 * (:beforeAt, :beforeId)} e também como {@code occurred_at <= :beforeAt}, que o planejador usa como limite do índice
 * (a comparação de linha sozinha não vira faixa de índice quando o {@code id} não está nele).
 */
@Repository
class JdbcCreditorActivityReader implements CreditorActivityReader {

    private static final String SQL = """
            with people as (
                select u.id, u.name from identity.creditor_user u
                where u.creditor_id = :creditorId and (cast(:person as uuid) is null or u.id = cast(:person as uuid))
            )
            select x.occurred_at, x.id, x.source, x.code, x.entity_type, x.entity_id, x.actor_id, p.name
            from (
                select a.occurred_at, a.id, 'AUDIT' as source, a.action as code, a.entity_type,
                       a.entity_id::text as entity_id, a.actor_id
                from shared.audit_event a
                where a.actor_type = 'CREDITOR' and a.actor_id in (select id from people)
                  and a.action in (:auditActions)
                  and a.occurred_at >= cast(:since as timestamptz)
                  and a.occurred_at <= cast(:beforeAt as timestamptz)
                  and (a.occurred_at, a.id) < (cast(:beforeAt as timestamptz), cast(:beforeId as uuid))
                union all
                select l.occurred_at, l.id, 'ACCESS', l.event_type, l.details ->> 'resource',
                       l.details ->> 'resourceId', l.actor_id
                from shared.access_log l
                where l.actor_type = 'CREDITOR' and l.actor_id in (select id from people)
                  and l.event_type in (:accessEvents)
                  and (l.event_type <> 'FILE_DOWNLOADED' or l.details ->> 'origin' = :downloadOrigin)
                  and l.occurred_at >= cast(:since as timestamptz)
                  and l.occurred_at <= cast(:beforeAt as timestamptz)
                  and (l.occurred_at, l.id) < (cast(:beforeAt as timestamptz), cast(:beforeId as uuid))
            ) x
            join people p on p.id = x.actor_id
            order by x.occurred_at desc, x.id desc
            limit :limit
            """;

    private final NamedParameterJdbcTemplate jdbc;

    JdbcCreditorActivityReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ActivityRow> read(UUID creditorId, UUID person, Collection<String> auditActions,
                                  Collection<String> accessEvents, String downloadOrigin, Instant since,
                                  Instant beforeAt, UUID beforeId, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("creditorId", creditorId)
                .addValue("person", person, java.sql.Types.OTHER)
                .addValue("auditActions", auditActions)
                .addValue("accessEvents", accessEvents)
                .addValue("downloadOrigin", downloadOrigin)
                .addValue("since", since.atOffset(ZoneOffset.UTC))
                .addValue("beforeAt", beforeAt.atOffset(ZoneOffset.UTC))
                .addValue("beforeId", beforeId)
                .addValue("limit", limit);
        return jdbc.query(SQL, params, (rs, i) -> new ActivityRow(
                rs.getObject(1, OffsetDateTime.class).toInstant(), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getString(4), rs.getString(5), rs.getString(6), rs.getObject(7, UUID.class), rs.getString(8)));
    }
}
