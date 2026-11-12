package br.com.gestorcobranca.costing.infrastructure.persistence;

import br.com.gestorcobranca.costing.application.port.out.CostFactStore;
import br.com.gestorcobranca.costing.domain.model.ChannelItem;
import br.com.gestorcobranca.costing.domain.model.CostCategory;
import br.com.gestorcobranca.costing.domain.model.CostFact;
import br.com.gestorcobranca.costing.domain.model.CostKey;
import br.com.gestorcobranca.costing.domain.model.CostWarning;
import br.com.gestorcobranca.costing.domain.model.CostWarningCode;
import br.com.gestorcobranca.costing.domain.model.ItemEstimate;
import br.com.gestorcobranca.costing.domain.model.OverheadAllocation;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Grava e lê os fatos apurados da assessoria corrente. Toda gravação usa {@code shared.current_tenant_id()} e passa
 * pela política de RLS (nada é gravado para outra assessoria).
 */
@Repository
class JdbcCostFactStore implements CostFactStore {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcCostFactStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Chave do lock da apuração: uma por assessoria (a mesma no modo que espera e no que só tenta). */
    private static final String LOCK_KEY = "hashtext('costing.refresh'), hashtext(shared.current_tenant_id()::text)";

    @Override
    public void lockForRefresh(Duration statementTimeout) {
        statementTimeout(statementTimeout);
        jdbc.queryForObject("select 1 from (select pg_advisory_xact_lock(" + LOCK_KEY + ")) l",
                new MapSqlParameterSource(), Integer.class);
    }

    @Override
    public boolean tryLockForRefresh(Duration statementTimeout) {
        statementTimeout(statementTimeout);
        return Boolean.TRUE.equals(jdbc.queryForObject("select pg_try_advisory_xact_lock(" + LOCK_KEY + ")",
                new MapSqlParameterSource(), Boolean.class));
    }

    private void statementTimeout(Duration timeout) {
        // set_config(..., true) = local à transação; valor numérico em milissegundos
        jdbc.queryForObject("select set_config('statement_timeout', :timeout, true)",
                new MapSqlParameterSource("timeout", String.valueOf(timeout.toMillis())), String.class);
    }

    @Override
    public long replaceFacts(LocalDate from, LocalDate to, Collection<CostCategory> categories,
                             Collection<CostFact> facts) {
        jdbc.update("""
                delete from costing.cost_daily
                 where day between :from and :to and category in (:categories) and basis = 'ESTIMATED'
                """, new MapSqlParameterSource().addValue("from", from, Types.DATE).addValue("to", to, Types.DATE)
                .addValue("categories", categories.stream().map(Enum::name).toList()));
        return insert(facts.stream().filter(f -> !f.day().isBefore(from) && !f.day().isAfter(to)
                && categories.contains(f.category())).toList(), "ESTIMATED");
    }

    @Override
    public long replaceInvoiceAdjustments(LocalDate from, LocalDate to, Collection<CostFact> facts) {
        jdbc.update("""
                delete from costing.cost_daily
                 where day between :from and :to and basis = 'INVOICE_ADJUSTMENT'
                """, new MapSqlParameterSource().addValue("from", from, Types.DATE).addValue("to", to, Types.DATE));
        return insert(facts.stream().filter(f -> !f.day().isBefore(from) && !f.day().isAfter(to)).toList(),
                "INVOICE_ADJUSTMENT");
    }

    /** Grava os fatos na base informada (lista fixa: {@code ESTIMATED} ou {@code INVOICE_ADJUSTMENT}). */
    private long insert(List<CostFact> rows, String basis) {
        if (rows.isEmpty()) {
            return 0;
        }
        SqlParameterSource[] batch = rows.stream().map(f -> new MapSqlParameterSource()
                .addValue("day", f.day(), Types.DATE)
                .addValue("creditor", f.creditorId())
                .addValue("portfolio", f.portfolioId())
                .addValue("category", f.category().name())
                .addValue("item", f.item() == null ? null : f.item().name(), Types.VARCHAR)
                .addValue("amount", f.amount())
                .addValue("quantity", f.quantity())
                .addValue("unit", f.unit().name())
                .addValue("basis", basis)).toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate("""
                insert into costing.cost_daily (tenant_id, day, creditor_id, portfolio_id, category, item, amount,
                                                quantity, unit, basis)
                values (shared.current_tenant_id(), :day, :creditor, :portfolio, :category, :item, :amount, :quantity,
                        :unit, :basis)
                """, batch);
        return rows.size();
    }

    @Override
    public Map<ChannelItem, Map<CostKey, ItemEstimate>> channelEstimates(YearMonth month) {
        Map<ChannelItem, Map<CostKey, ItemEstimate>> out = new EnumMap<>(ChannelItem.class);
        jdbc.query("""
                select day, creditor_id, portfolio_id, item, sum(amount) as amount, sum(quantity) as quantity
                  from costing.cost_daily
                 where day between :from and :to and basis = 'ESTIMATED' and item is not null
                 group by 1, 2, 3, 4
                """, new MapSqlParameterSource().addValue("from", month.atDay(1), Types.DATE)
                .addValue("to", month.atEndOfMonth(), Types.DATE), rs -> {
            out.computeIfAbsent(ChannelItem.valueOf(rs.getString("item")), k -> new LinkedHashMap<>())
                    .put(new CostKey(JdbcUsageReader.day(rs), JdbcUsageReader.uuid(rs, "creditor_id"),
                                    JdbcUsageReader.uuid(rs, "portfolio_id")),
                            new ItemEstimate(rs.getBigDecimal("amount"), rs.getBigDecimal("quantity")));
        });
        return out;
    }

    @Override
    public boolean hasUnitemizedMessaging(YearMonth month) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (select 1 from costing.cost_daily
                                where day between :from and :to and category = 'MESSAGING' and item is null)
                """, new MapSqlParameterSource().addValue("from", month.atDay(1), Types.DATE)
                .addValue("to", month.atEndOfMonth(), Types.DATE), Boolean.class));
    }

    @Override
    public void replaceWarnings(LocalDate from, LocalDate to, Collection<CostWarningCode> codes,
                                Collection<CostWarning> warnings) {
        jdbc.update("delete from costing.cost_warning_daily where day between :from and :to and code in (:codes)",
                new MapSqlParameterSource().addValue("from", from, Types.DATE).addValue("to", to, Types.DATE)
                        .addValue("codes", codes.stream().map(Enum::name).toList()));
        SqlParameterSource[] batch = warnings.stream()
                .filter(w -> !w.day().isBefore(from) && !w.day().isAfter(to) && codes.contains(w.code()))
                .map(w -> new MapSqlParameterSource()
                        .addValue("day", w.day(), Types.DATE)
                        .addValue("creditor", w.creditorId())
                        .addValue("portfolio", w.portfolioId())
                        .addValue("code", w.code().name())
                        .addValue("quantity", w.quantity())).toArray(SqlParameterSource[]::new);
        if (batch.length > 0) {
            jdbc.batchUpdate("""
                    insert into costing.cost_warning_daily (tenant_id, day, creditor_id, portfolio_id, code, quantity)
                    values (shared.current_tenant_id(), :day, :creditor, :portfolio, :code, :quantity)
                    """, batch);
        }
    }

    @Override
    public long replaceActivity(LocalDate from, LocalDate to) {
        MapSqlParameterSource p = JdbcUsageReader.period(from, to);
        jdbc.update("delete from costing.activity_daily where day between :from and :to", p);
        // receita da assessoria = comissão + valor fixo + honorários da assessoria dos lançamentos do recebimento
        // (os de estorno já vêm negativos); recuperado = recebimentos líquidos de estornos na data do fato
        return jdbc.update("""
                insert into costing.activity_daily (tenant_id, day, creditor_id, portfolio_id, recovered,
                                                    recovered_gross, revenue, settled_agreements, worked_cases)
                select shared.current_tenant_id(), u.day, u.creditor_id, u.portfolio_id, sum(u.recovered),
                       sum(u.recovered_gross), sum(u.revenue), sum(u.settled), sum(u.cases)
                  from (select r.competence_date as day, r.creditor_id, r.portfolio_id, r.amount as recovered,
                               case when r.kind = 'PAYMENT' and r.amount > 0 then r.amount else 0 end as recovered_gross,
                               coalesce(ce.revenue, 0) as revenue, 0 as settled, 0 as cases
                          from costing.src_receipt r
                          left join lateral (select sum(e.commission_amount + e.fixed_fee + e.agency_honorarium)
                                                    as revenue
                                               from costing.src_commission_entry e
                                              where e.receipt_id = r.receipt_id) ce on true
                         where r.competence_date between :from and :to
                        union all
                        select (a.settled_at at time zone :zone)::date, a.creditor_id, a.portfolio_id, 0, 0, 0, 1, 0
                          from costing.src_agreement a
                         where a.status = 'SETTLED' and a.settled_at >= :fromTs and a.settled_at < :toTs
                        union all
                        select w.day, w.creditor_id, w.portfolio_id, 0, 0, 0, 0, count(distinct w.case_id)
                          from (select (i.started_at at time zone :zone)::date as day, i.creditor_id, i.portfolio_id,
                                       i.case_id
                                  from costing.src_interaction i
                                 where i.started_at >= :fromTs and i.started_at < :toTs) w
                         group by w.day, w.creditor_id, w.portfolio_id) u
                 where u.creditor_id is not null
                 group by u.day, u.creditor_id, u.portfolio_id
                """, p);
    }

    @Override
    public Map<CostKey, BigDecimal> overheadBasis(YearMonth month, OverheadAllocation allocation) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("from", month.atDay(1), Types.DATE)
                .addValue("to", month.atEndOfMonth(), Types.DATE);
        String sql = switch (allocation) {
            case HOURS -> """
                    select day, creditor_id, portfolio_id, sum(quantity) as basis
                      from costing.cost_daily
                     where day between :from and :to and category in ('HUMAN_DIRECT', 'HUMAN_IDLE')
                       and basis = 'ESTIMATED' and creditor_id is not null
                     group by 1, 2, 3
                    """;
            case RECOVERED -> """
                    select day, creditor_id, portfolio_id, sum(recovered_gross) as basis
                      from costing.activity_daily where day between :from and :to group by 1, 2, 3
                    """;
            case CASES -> """
                    select day, creditor_id, portfolio_id, sum(worked_cases) as basis
                      from costing.activity_daily where day between :from and :to group by 1, 2, 3
                    """;
            case CREDITOR -> null;
        };
        Map<CostKey, BigDecimal> basis = new LinkedHashMap<>();
        if (sql == null) {
            return basis;
        }
        jdbc.query(sql, p, rs -> {
            basis.put(new CostKey(JdbcUsageReader.day(rs), JdbcUsageReader.uuid(rs, "creditor_id"),
                    JdbcUsageReader.uuid(rs, "portfolio_id")), rs.getBigDecimal("basis"));
        });
        return basis;
    }

    @Override
    public void saveState(LocalDate from, LocalDate to, Instant refreshedAt, long durationMs, long rows, int version) {
        // last_day só avança: reapurar um período antigo não "desapura" os dias posteriores
        jdbc.update("""
                insert into costing.refresh_state (tenant_id, last_day, last_from, refreshed_at, duration_ms, row_count,
                                                   version)
                values (shared.current_tenant_id(), :to, :from, :at, :duration, :rows, :version)
                on conflict (tenant_id) do update set last_day = greatest(costing.refresh_state.last_day, excluded.last_day),
                    last_from = excluded.last_from, refreshed_at = excluded.refreshed_at,
                    duration_ms = excluded.duration_ms, row_count = excluded.row_count, version = excluded.version
                """, new MapSqlParameterSource().addValue("from", from, Types.DATE).addValue("to", to, Types.DATE)
                .addValue("at", refreshedAt.atOffset(ZoneOffset.UTC), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("duration", durationMs).addValue("rows", rows).addValue("version", version));
    }

    @Override
    public Optional<RefreshState> state() {
        return jdbc.query("select last_day, last_from, refreshed_at, version from costing.refresh_state",
                new MapSqlParameterSource(), (rs, i) -> new RefreshState(rs.getObject("last_day", LocalDate.class),
                        rs.getObject("last_from", LocalDate.class),
                        rs.getObject("refreshed_at", OffsetDateTime.class).toInstant(), rs.getInt("version")))
                .stream().findFirst();
    }
}
