package br.com.gestorcobranca.publicapi.infrastructure.persistence;

import br.com.gestorcobranca.publicapi.application.port.out.UsageDelta;
import br.com.gestorcobranca.publicapi.application.port.out.UsageStore;
import br.com.gestorcobranca.publicapi.domain.model.UsageTotals;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Consumo agregado por hora em {@code publicapi.usage_hourly}: gravação por soma em lote ({@code insert … on conflict
 * do update}) e leituras agregadas. Sempre dentro da transação do tenant (RLS); o filtro de credores é aplicado
 * explicitamente.
 */
@Component
class JdbcUsageStore implements UsageStore {

    private static final String SUMS = """
            coalesce(sum(requests), 0) as requests, coalesce(sum(throttled), 0) as throttled,
            coalesce(sum(client_errors), 0) as client_errors, coalesce(sum(server_errors), 0) as server_errors,
            coalesce(sum(bytes_in), 0) as bytes_in, coalesce(sum(bytes_out), 0) as bytes_out,
            coalesce(sum(billable_bytes_in), 0) as billable_bytes_in,
            coalesce(sum(billable_bytes_out), 0) as billable_bytes_out,
            coalesce(sum(records), 0) as records
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final CurrentActor currentActor;

    JdbcUsageStore(NamedParameterJdbcTemplate jdbc, CurrentActor currentActor) {
        this.jdbc = jdbc;
        this.currentActor = currentActor;
    }

    @Override
    public void add(List<UsageDelta> deltas) {
        if (deltas.isEmpty()) {
            return;
        }
        UUID tenantId = currentActor.tenantId();
        // ordem estável das chaves: instâncias gravando ao mesmo tempo bloqueiam as linhas na mesma ordem (sem deadlock)
        List<UsageDelta> ordered = new ArrayList<>(deltas);
        ordered.sort(Comparator.comparing((UsageDelta d) -> d.creditorId().toString())
                .thenComparing(d -> d.tokenId().toString()).thenComparing(UsageDelta::hourStart)
                .thenComparing(UsageDelta::method).thenComparing(UsageDelta::route));
        SqlParameterSource[] batch = ordered.stream().filter(d -> d.tenantId().equals(tenantId)).map(d ->
                new MapSqlParameterSource().addValue("tenant", tenantId).addValue("creditor", d.creditorId())
                        .addValue("token", d.tokenId()).addValue("hour", Timestamp.from(d.hourStart()))
                        .addValue("method", d.method()).addValue("route", truncate(d.route()))
                        .addValue("requests", d.requests()).addValue("throttled", d.throttled())
                        .addValue("clientErrors", d.clientErrors()).addValue("serverErrors", d.serverErrors())
                        .addValue("bytesIn", d.bytesIn()).addValue("bytesOut", d.bytesOut())
                        .addValue("billableBytesIn", d.billableBytesIn())
                        .addValue("billableBytesOut", d.billableBytesOut())
                        .addValue("records", d.records()).addValue("latency", d.latencyMsTotal()))
                .toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate("""
                insert into publicapi.usage_hourly (tenant_id, creditor_id, token_id, hour_start, method, route, requests,
                    throttled, client_errors, server_errors, bytes_in, bytes_out, billable_bytes_in, billable_bytes_out,
                    records, latency_ms_total, updated_at)
                values (:tenant, :creditor, :token, :hour, :method, :route, :requests, :throttled, :clientErrors,
                    :serverErrors, :bytesIn, :bytesOut, :billableBytesIn, :billableBytesOut, :records, :latency, now())
                on conflict (tenant_id, creditor_id, token_id, hour_start, method, route) do update set
                    requests = usage_hourly.requests + excluded.requests,
                    throttled = usage_hourly.throttled + excluded.throttled,
                    client_errors = usage_hourly.client_errors + excluded.client_errors,
                    server_errors = usage_hourly.server_errors + excluded.server_errors,
                    bytes_in = usage_hourly.bytes_in + excluded.bytes_in,
                    bytes_out = usage_hourly.bytes_out + excluded.bytes_out,
                    billable_bytes_in = usage_hourly.billable_bytes_in + excluded.billable_bytes_in,
                    billable_bytes_out = usage_hourly.billable_bytes_out + excluded.billable_bytes_out,
                    records = usage_hourly.records + excluded.records,
                    latency_ms_total = usage_hourly.latency_ms_total + excluded.latency_ms_total,
                    updated_at = now()
                """, batch);
    }

    @Override
    public void touchTokens(List<LastUse> uses) {
        if (uses.isEmpty()) {
            return;
        }
        SqlParameterSource[] batch = uses.stream().sorted(Comparator.comparing(u -> u.tokenId().toString()))
                .map(u -> new MapSqlParameterSource().addValue("id", u.tokenId()).addValue("at", Timestamp.from(u.at()))
                        .addValue("ip", u.ip() == null || u.ip().length() > 45 ? null : u.ip()))
                .toArray(SqlParameterSource[]::new);
        // só avança; não altera versão nem metadados do token (não é alteração do usuário)
        jdbc.batchUpdate("""
                update publicapi.integration_token set last_used_at = :at, last_used_ip = :ip
                 where id = :id and (last_used_at is null or last_used_at < :at)
                """, batch);
    }

    @Override
    public UsageTotals totals(Filter f) {
        MapSqlParameterSource p = params(f);
        return jdbc.queryForObject("select " + SUMS + " from publicapi.usage_hourly where " + where(f, p), p,
                (rs, i) -> totals(rs));
    }

    @Override
    public List<DailyRow> daily(Filter f, String zoneId) {
        MapSqlParameterSource p = params(f).addValue("zone", zoneId);
        return jdbc.query("select (hour_start at time zone :zone)::date as day, " + SUMS
                        + " from publicapi.usage_hourly where " + where(f, p) + " group by 1 order by 1", p,
                (rs, i) -> new DailyRow(rs.getObject("day", java.time.LocalDate.class), totals(rs)));
    }

    @Override
    public List<RouteRow> byRoute(Filter f) {
        MapSqlParameterSource p = params(f);
        return jdbc.query("select method, route, " + SUMS + " from publicapi.usage_hourly where " + where(f, p)
                        + " group by method, route order by sum(requests) desc, method, route", p,
                (rs, i) -> new RouteRow(rs.getString("method"), rs.getString("route"), totals(rs)));
    }

    @Override
    public List<TokenRow> byToken(Filter f) {
        MapSqlParameterSource p = params(f);
        return jdbc.query("select token_id, creditor_id, " + SUMS + " from publicapi.usage_hourly where " + where(f, p)
                        + " group by token_id, creditor_id order by sum(requests) desc, token_id", p,
                (rs, i) -> new TokenRow(rs.getObject("token_id", UUID.class), rs.getObject("creditor_id", UUID.class),
                        totals(rs)));
    }

    @Override
    public void detailed(Filter f, String zoneId, Consumer<DetailRow> sink) {
        MapSqlParameterSource p = params(f).addValue("zone", zoneId);
        jdbc.query("select (hour_start at time zone :zone)::date as day, creditor_id, token_id, method, route, " + SUMS
                + ", coalesce(sum(latency_ms_total), 0) as latency from publicapi.usage_hourly where " + where(f, p)
                + " group by 1, creditor_id, token_id, method, route order by 1, creditor_id, token_id, method, route", p,
                rs -> {
                    sink.accept(new DetailRow(rs.getObject("day", java.time.LocalDate.class),
                            rs.getObject("creditor_id", UUID.class), rs.getObject("token_id", UUID.class),
                            rs.getString("method"), rs.getString("route"), totals(rs), rs.getLong("latency")));
                });
    }

    @Override
    public java.util.Optional<java.time.Instant> firstUsageAt(UUID creditorId, java.time.Instant from) {
        List<Timestamp> first = jdbc.queryForList("""
                select min(hour_start) from publicapi.usage_hourly
                 where creditor_id = :creditor and hour_start >= :from and (requests > 0 or records > 0)
                """, new MapSqlParameterSource("creditor", creditorId).addValue("from", Timestamp.from(from)),
                Timestamp.class);
        return first.isEmpty() || first.getFirst() == null ? java.util.Optional.empty()
                : java.util.Optional.of(first.getFirst().toInstant());
    }

    private static MapSqlParameterSource params(Filter f) {
        return new MapSqlParameterSource().addValue("from", Timestamp.from(f.from())).addValue("to", Timestamp.from(f.to()));
    }

    private static String where(Filter f, MapSqlParameterSource p) {
        StringBuilder sql = new StringBuilder("hour_start >= :from and hour_start < :to");
        if (f.creditorIds() != null) {
            if (f.creditorIds().isEmpty()) {
                sql.append(" and false");
            } else {
                sql.append(" and creditor_id in (:creditors)");
                p.addValue("creditors", f.creditorIds());
            }
        }
        if (f.creditorId() != null) {
            sql.append(" and creditor_id = :creditor");
            p.addValue("creditor", f.creditorId());
        }
        if (f.tokenId() != null) {
            sql.append(" and token_id = :token");
            p.addValue("token", f.tokenId());
        }
        return sql.toString();
    }

    private static UsageTotals totals(ResultSet rs) throws SQLException {
        return new UsageTotals(rs.getLong("requests"), rs.getLong("throttled"), rs.getLong("client_errors"),
                rs.getLong("server_errors"), rs.getLong("bytes_in"), rs.getLong("bytes_out"),
                rs.getLong("billable_bytes_in"), rs.getLong("billable_bytes_out"), rs.getLong("records"));
    }

    private static String truncate(String route) {
        return route == null ? "UNMATCHED" : route.length() <= 200 ? route : route.substring(0, 200);
    }
}
