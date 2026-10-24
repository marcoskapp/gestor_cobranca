package br.com.gestorcobranca.analytics.infrastructure.persistence;

import br.com.gestorcobranca.analytics.application.port.out.SnapshotStore;
import br.com.gestorcobranca.shared.domain.Guard;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;

/**
 * Chama as funções {@code analytics.refresh_*} (executam como o chamador — app_runtime — sob RLS da
 * assessoria) e grava o carimbo "dados até".
 */
@Repository
class JdbcSnapshotStore implements SnapshotStore {

    /** Conjunto → chamada da função de reconstrução (lista fixa: nada vem do cliente). */
    private static final Map<String, String> CALLS = Map.of(
            "recovery_daily", "select analytics.refresh_recovery_daily()",
            "agreement_cohort", "select analytics.refresh_agreement_cohort(:zone)",
            "portfolio_position", "select analytics.refresh_portfolio_position(:reference)",
            "case_position", "select analytics.refresh_case_position(:reference)");

    private final NamedParameterJdbcTemplate jdbc;

    JdbcSnapshotStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void lockForRefresh(Duration statementTimeout) {
        // set_config(..., true) = local à transação; valor numérico em milissegundos
        jdbc.queryForObject("select set_config('statement_timeout', :timeout, true)",
                new MapSqlParameterSource("timeout", String.valueOf(statementTimeout.toMillis())), String.class);
        jdbc.queryForObject("select 1 from (select pg_advisory_xact_lock(hashtext('analytics.refresh'), "
                + "hashtext(analytics.require_tenant()::text))) l", new MapSqlParameterSource(), Integer.class);
    }

    @Override
    public long refresh(String dataset, LocalDate referenceDate, String zoneId) {
        String call = CALLS.get(dataset);
        Guard.require(call != null, "analytics.dataset_invalid", "Conjunto de dados desconhecido: " + dataset);
        Long rows = jdbc.queryForObject(call, new MapSqlParameterSource().addValue("zone", zoneId)
                .addValue("reference", referenceDate, Types.DATE), Long.class);
        return rows == null ? 0 : rows;
    }

    @Override
    public void saveState(String dataset, Instant dataUntil, Instant refreshedAt, long durationMs, long rows) {
        jdbc.update("""
                insert into analytics.refresh_state (tenant_id, dataset, data_until, refreshed_at, duration_ms, row_count)
                values (analytics.require_tenant(), :dataset, :until, :at, :duration, :rows)
                on conflict (tenant_id, dataset) do update set data_until = excluded.data_until,
                    refreshed_at = excluded.refreshed_at, duration_ms = excluded.duration_ms,
                    row_count = excluded.row_count
                """, new MapSqlParameterSource().addValue("dataset", dataset)
                .addValue("until", SqlWhere.ts(dataUntil), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("at", SqlWhere.ts(refreshedAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("duration", durationMs).addValue("rows", rows));
    }

    @Override
    public Optional<Instant> lastRefreshedAt() {
        OffsetDateTime value = jdbc.queryForObject("select max(refreshed_at) from analytics.refresh_state",
                new MapSqlParameterSource(), OffsetDateTime.class);
        return Optional.ofNullable(value).map(OffsetDateTime::toInstant);
    }
}
