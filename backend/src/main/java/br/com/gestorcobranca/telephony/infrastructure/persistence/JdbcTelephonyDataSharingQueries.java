package br.com.gestorcobranca.telephony.infrastructure.persistence;

import br.com.gestorcobranca.shared.privacy.DataSharingRows;
import br.com.gestorcobranca.shared.privacy.DataSharingUsage;
import br.com.gestorcobranca.telephony.application.port.out.TelephonyDataSharingQueries;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Índice: {@code ix_call_debtor (tenant_id, debtor_id) where debtor_id is not null}. Conta só a chamada que chegou à
 * plataforma (discagem iniciada, id do provedor ou atendida): a recusada pelo porteiro de contato não sai da assessoria.
 */
@Component
class JdbcTelephonyDataSharingQueries implements TelephonyDataSharingQueries {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcTelephonyDataSharingQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<DataSharingUsage> callsByProvider(UUID debtorId, Set<UUID> creditorScope) {
        if (creditorScope != null && creditorScope.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource("debtor", debtorId);
        StringBuilder sql = new StringBuilder("""
                select c.provider as code, null::uuid as recipient_id, c.provider as name,
                       min(c.queued_at) as first_at, max(c.queued_at) as last_at, count(*) as total,
                       (array_agg(c.id order by c.queued_at desc))[1] as latest_id
                  from telephony.phone_call c
                 where c.debtor_id = :debtor
                   and (c.dial_started_at is not null or c.provider_call_id is not null or c.answered_at is not null)
                """);
        if (creditorScope != null) {
            sql.append(" and c.creditor_id in (:scope)");
            params.addValue("scope", creditorScope);
        }
        sql.append(" group by c.provider order by min(c.queued_at)");
        return jdbc.query(sql.toString(), params, DataSharingRows.USAGE);
    }

    @Override
    public List<String> activeProviders() {
        return jdbc.queryForList("select distinct provider from telephony.provider_account where active order by 1",
                new MapSqlParameterSource(), String.class);
    }
}
