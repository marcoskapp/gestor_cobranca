package br.com.gestorcobranca.assistant.infrastructure.persistence;

import br.com.gestorcobranca.assistant.application.port.out.AssistantDataSharingQueries;
import br.com.gestorcobranca.shared.privacy.DataSharingRows;
import br.com.gestorcobranca.shared.privacy.DataSharingUsage;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Índice: {@code ix_assistant_turn_debtor (tenant_id, debtor_id)}. Turno sem chamada ao modelo não tem provedor. */
@Component
class JdbcAssistantDataSharingQueries implements AssistantDataSharingQueries {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcAssistantDataSharingQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<DataSharingUsage> turnsByProvider(UUID debtorId, Set<UUID> creditorScope) {
        if (creditorScope != null && creditorScope.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource("debtor", debtorId);
        StringBuilder sql = new StringBuilder("""
                select t.provider as code, null::uuid as recipient_id, t.provider as name,
                       min(t.started_at) as first_at, max(t.started_at) as last_at, count(*) as total,
                       (array_agg(t.id order by t.started_at desc))[1] as latest_id
                  from assistant.assistant_turn t
                 where t.debtor_id = :debtor and t.provider is not null and t.model_calls > 0
                """);
        if (creditorScope != null) {
            sql.append(" and t.creditor_id in (:scope)");
            params.addValue("scope", creditorScope);
        }
        sql.append(" group by t.provider order by min(t.started_at)");
        return jdbc.query(sql.toString(), params, DataSharingRows.USAGE);
    }

    @Override
    public boolean hasActivePublishedPolicy() {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (select 1 from assistant.assistant_policy where status = 'PUBLISHED' and mode <> 'OFF')""",
                new MapSqlParameterSource(), Boolean.class));
    }
}
