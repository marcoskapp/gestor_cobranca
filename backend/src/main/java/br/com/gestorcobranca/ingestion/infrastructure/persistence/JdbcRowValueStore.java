package br.com.gestorcobranca.ingestion.infrastructure.persistence;

import br.com.gestorcobranca.ingestion.application.port.out.RowValueStore;
import br.com.gestorcobranca.ingestion.domain.model.review.ComputedTotals;
import br.com.gestorcobranca.ingestion.domain.model.review.RowValue;
import br.com.gestorcobranca.ingestion.domain.model.review.TotalsMeasure;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Valores por linha da prévia e agregações da conferência em SQL. O tenant vem de {@code shared.current_tenant_id()}
 * (o mesmo valor que o RLS exige), definido pelo gerenciador de transações. Linha excluída = alerta {@code EXCLUDED}.
 */
@Repository
class JdbcRowValueStore implements RowValueStore {

    private static final int FETCH_SIZE = 2000;
    private static final int TOP_PRODUCTS = 20;
    private static final String EXCLUDED = """
            exists (select 1 from ingestion.import_review_alert a
                    where a.batch_id = v.batch_id and a.row_number = v.row_number and a.status = 'EXCLUDED')""";

    private final NamedParameterJdbcTemplate jdbc;
    private final JdbcTemplate streaming;

    JdbcRowValueStore(NamedParameterJdbcTemplate jdbc, DataSource dataSource) {
        this.jdbc = jdbc;
        this.streaming = new JdbcTemplate(dataSource);
        this.streaming.setFetchSize(FETCH_SIZE);
    }

    @Override
    public void insertAll(UUID batchId, List<RowValue> rows) {
        if (rows.isEmpty()) {
            return;
        }
        SqlParameterSource[] params = rows.stream().map(r -> new MapSqlParameterSource()
                .addValue("batch", batchId)
                .addValue("row", r.rowNumber())
                .addValue("valid", r.valid())
                .addValue("principal", r.principal(), Types.NUMERIC)
                .addValue("principalInvalid", r.principalInvalid())
                .addValue("principalAmbiguous", r.principalAmbiguous())
                .addValue("balance", r.balance(), Types.NUMERIC)
                .addValue("balanceInvalid", r.balanceInvalid())
                .addValue("contract", r.contractAmount(), Types.NUMERIC)
                .addValue("contractInvalid", r.contractInvalid())
                .addValue("due", r.dueDate(), Types.DATE)
                .addValue("situation", r.situation(), Types.VARCHAR)
                .addValue("product", r.product(), Types.VARCHAR)
                .addValue("contractNumber", r.contractNumber(), Types.VARCHAR)
                .addValue("externalId", r.externalId(), Types.VARCHAR)
                .addValue("debtor", r.debtorKey(), Types.VARCHAR)).toArray(SqlParameterSource[]::new);
        jdbc.batchUpdate("""
                insert into ingestion.import_row_value (tenant_id, batch_id, row_number, valid, principal,
                    principal_invalid, principal_ambiguous, balance, balance_invalid, contract_amount, contract_invalid,
                    due_date, situation, product, contract_number, external_id, debtor_key)
                values (shared.current_tenant_id(), :batch, :row, :valid, :principal, :principalInvalid,
                    :principalAmbiguous, :balance, :balanceInvalid, :contract, :contractInvalid, :due, :situation,
                    :product, :contractNumber, :externalId, :debtor)
                on conflict do nothing
                """, params);
    }

    @Override
    public ComputedTotals totals(UUID batchId) {
        return jdbc.queryForObject("""
                select count(*) as rows,
                       coalesce(sum(principal), 0) as principal, coalesce(sum(balance), 0) as balance,
                       coalesce(sum(contract_amount), 0) as contract,
                       count(*) filter (where principal_invalid) as principal_invalid,
                       count(*) filter (where balance_invalid) as balance_invalid,
                       count(*) filter (where contract_invalid) as contract_invalid
                from ingestion.import_row_value where batch_id = :batch
                """, Map.of("batch", batchId), (rs, i) -> {
            Map<TotalsMeasure, BigDecimal> sums = new EnumMap<>(TotalsMeasure.class);
            sums.put(TotalsMeasure.PRINCIPAL, rs.getBigDecimal("principal"));
            sums.put(TotalsMeasure.BALANCE, rs.getBigDecimal("balance"));
            sums.put(TotalsMeasure.CONTRACT_AMOUNT, rs.getBigDecimal("contract"));
            Map<TotalsMeasure, Long> invalid = new EnumMap<>(TotalsMeasure.class);
            invalid.put(TotalsMeasure.PRINCIPAL, rs.getLong("principal_invalid"));
            invalid.put(TotalsMeasure.BALANCE, rs.getLong("balance_invalid"));
            invalid.put(TotalsMeasure.CONTRACT_AMOUNT, rs.getLong("contract_invalid"));
            return new ComputedTotals(rs.getLong("rows"), sums, invalid);
        });
    }

    @Override
    public List<BreakdownRow> breakdown(UUID batchId, LocalDate today) {
        MapSqlParameterSource p = new MapSqlParameterSource("batch", batchId).addValue("today", today, Types.DATE)
                .addValue("top", TOP_PRODUCTS);
        return jdbc.query("""
                with v as (
                    select v.*, (%s) as excluded,
                           case when v.due_date is null then 'INVALID'
                                when v.due_date >= :today then 'NOT_DUE'
                                when :today - v.due_date <= 30 then 'D1_30'
                                when :today - v.due_date <= 60 then 'D31_60'
                                when :today - v.due_date <= 90 then 'D61_90'
                                when :today - v.due_date <= 180 then 'D91_180'
                                when :today - v.due_date <= 360 then 'D181_360'
                                when :today - v.due_date <= 720 then 'D361_720'
                                else 'D720_PLUS' end as aging
                    from ingestion.import_row_value v where v.batch_id = :batch),
                products as (
                    select coalesce(product, '') as product from v group by coalesce(product, '')
                    order by count(*) desc, coalesce(product, '') limit :top)
                select 'AGING' as dimension, aging as key, count(*) as rows, coalesce(sum(principal), 0) as principal,
                       coalesce(sum(balance), 0) as balance from v group by aging
                union all
                select 'SITUATION', situation, count(*), coalesce(sum(principal), 0), coalesce(sum(balance), 0)
                from v group by situation
                union all
                select 'PRODUCT', case when p.product is null then 'OTHERS' else coalesce(v.product, '') end, count(*),
                       coalesce(sum(v.principal), 0), coalesce(sum(v.balance), 0)
                from v left join products p on p.product = coalesce(v.product, '')
                group by case when p.product is null then 'OTHERS' else coalesce(v.product, '') end
                union all
                select 'VALIDITY', case when excluded then 'EXCLUDED' when valid then 'VALID' else 'ERROR' end, count(*),
                       coalesce(sum(principal), 0), coalesce(sum(balance), 0)
                from v group by case when excluded then 'EXCLUDED' when valid then 'VALID' else 'ERROR' end
                order by 1, 3 desc
                """.formatted(EXCLUDED), p, (rs, i) -> new BreakdownRow(rs.getString(1), rs.getString(2), rs.getLong(3),
                rs.getBigDecimal(4), rs.getBigDecimal(5)));
    }

    @Override
    public Suspects suspects(UUID batchId, TotalsMeasure measure, BigDecimal difference, int limit) {
        String column = switch (measure) {
            case BALANCE -> "balance";
            case CONTRACT_AMOUNT -> "contract_amount";
            default -> "principal";
        };
        MapSqlParameterSource p = new MapSqlParameterSource("batch", batchId).addValue("limit", limit);
        List<SuspectRow> largest = jdbc.query("""
                select row_number, external_id, %1$s, principal_ambiguous from ingestion.import_row_value
                where batch_id = :batch and %1$s is not null
                order by %2$s %1$s desc, row_number limit :limit
                """.formatted(column, measure == TotalsMeasure.PRINCIPAL ? "principal_ambiguous desc," : ""), p,
                (rs, i) -> suspect(rs));
        List<SuspectRow> equal = difference == null || difference.signum() == 0 ? List.of() : jdbc.query("""
                select row_number, external_id, %1$s, principal_ambiguous from ingestion.import_row_value
                where batch_id = :batch and %1$s = :amount order by row_number limit 5
                """.formatted(column), p.addValue("amount", difference.abs()), (rs, i) -> suspect(rs));
        return new Suspects(largest, equal);
    }

    private static SuspectRow suspect(ResultSet rs) throws SQLException {
        return new SuspectRow(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3), rs.getBoolean(4));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public void forEachValid(UUID batchId, Consumer<AnalysisRow> consumer) {
        streaming.query("""
                        select row_number, external_id, principal, balance, due_date, product
                        from ingestion.import_row_value
                        where batch_id = ? and valid and principal > 0
                        order by row_number
                        """,
                rs -> {
                    consumer.accept(new AnalysisRow(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3),
                            rs.getBigDecimal(4), rs.getObject(5, LocalDate.class), rs.getString(6)));
                }, batchId);
    }

    @Override
    public List<DebtorOutlier> debtorOutliers(UUID batchId, double ratio, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource("batch", batchId).addValue("ratio", ratio)
                .addValue("limit", limit);
        return jdbc.query("""
                with med as (
                    select debtor_key, percentile_disc(0.5) within group (order by principal) as m
                    from ingestion.import_row_value
                    where batch_id = :batch and valid and debtor_key is not null and principal > 0
                    group by debtor_key having count(*) >= 2)
                select v.row_number, v.external_id, v.principal, med.m
                from ingestion.import_row_value v join med on med.debtor_key = v.debtor_key
                where v.batch_id = :batch and v.valid and v.principal > 0
                  and (v.principal >= med.m * :ratio or v.principal * :ratio <= med.m)
                order by v.row_number limit :limit
                """, p, (rs, i) -> new DebtorOutlier(rs.getLong(1), rs.getString(2), rs.getBigDecimal(3),
                rs.getBigDecimal(4)));
    }

    @Override
    public List<ContractSum> contractCandidates(UUID batchId, double overRatio, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource("batch", batchId).addValue("ratio", overRatio)
                .addValue("limit", limit);
        return jdbc.query("""
                select contract_number, count(*), sum(principal), min(contract_amount), min(row_number),
                       (array_agg(external_id order by row_number))[1]
                from ingestion.import_row_value
                where batch_id = :batch and valid and contract_number is not null and contract_amount > 0
                  and principal is not null
                group by contract_number
                having count(*) >= 2 and min(contract_amount) = max(contract_amount)
                   and (sum(principal) > min(contract_amount) * :ratio or sum(principal) < min(contract_amount) * 0.15)
                order by min(row_number) limit :limit
                """, p, (rs, i) -> new ContractSum(rs.getString(1), rs.getLong(2), rs.getBigDecimal(3),
                rs.getBigDecimal(4), rs.getLong(5), rs.getString(6)));
    }

    @Override
    public Impact impact(UUID batchId, UUID creditorId) {
        MapSqlParameterSource p = new MapSqlParameterSource("batch", batchId).addValue("creditor", creditorId);
        return jdbc.queryForObject("""
                with v as (
                    select v.valid, v.principal, v.balance, v.situation, (%s) as excluded,
                           exists (select 1 from ingestion.import_batch_row r
                                   join ingestion.import_batch b on b.id = r.batch_id
                                   where b.creditor_id = :creditor and r.external_id = v.external_id
                                     and r.title_id is not null) as existing
                    from ingestion.import_row_value v where v.batch_id = :batch),
                i as (select * from v where valid and not excluded)
                select (select count(*) from v),
                       (select count(*) from i),
                       (select count(*) from v where not valid),
                       (select count(*) from v where excluded),
                       (select count(*) from i where not existing and situation not in ('PAID', 'CANCELLED')),
                       (select count(*) from i where existing),
                       (select count(*) from i where situation in ('SUSPENDED', 'DISPUTED')),
                       (select count(*) from i where situation in ('PAID', 'CANCELLED')),
                       (select coalesce(sum(principal), 0) from i where situation not in ('PAID', 'CANCELLED')),
                       (select coalesce(sum(balance), 0) from i where situation not in ('PAID', 'CANCELLED'))
                """.formatted(EXCLUDED), p, (rs, i) -> new Impact(rs.getLong(1), rs.getLong(2), rs.getLong(3),
                rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getBigDecimal(9),
                rs.getBigDecimal(10)));
    }
}
