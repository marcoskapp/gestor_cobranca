package br.com.gestorcobranca.debt.infrastructure.persistence;

import br.com.gestorcobranca.debt.application.port.out.CreditorReportedTitlesReader;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Títulos zerados pela carga do credor e títulos suspensos porque o credor os informou como pagos ou cancelados, sob RLS
 * (assessoria) e com o escopo por credor escrito em cada parte da consulta ({@code creditor_id in (:creditors)}), igual
 * para as linhas e para os totais (RS-94). Só lê o schema do debt. Índices {@code ix_debt_title_settled_by_import} e
 * {@code ix_debt_title_creditor_report} ({@code V0314}). Só os títulos com o motivo codificado (o texto livre do motivo
 * nunca é lido; decisão 1579).
 */
@Component
class JdbcCreditorReportedTitlesReader implements CreditorReportedTitlesReader {

    private static final Set<String> ORDER_COLUMNS = Set.of("occurred_at", "amount", "title_id");

    private final NamedParameterJdbcTemplate jdbc;

    JdbcCreditorReportedTitlesReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Row> page(Criteria criteria, List<Order> order, long offset, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String source = source(criteria, params);
        if (source == null) {
            return List.of();
        }
        List<String> orderBy = new ArrayList<>();
        for (Order o : order) {
            if (!ORDER_COLUMNS.contains(o.column())) {
                throw new IllegalArgumentException("Coluna de ordenação não permitida: " + o.column());
            }
            orderBy.add(o.column() + (o.ascending() ? " asc" : " desc"));
        }
        params.addValue("offset", offset).addValue("limit", limit);
        return jdbc.query("select * from (" + source + ") x order by " + String.join(", ", orderBy)
                + " offset :offset limit :limit", params, (rs, i) -> new Row(rs.getObject("title_id", UUID.class),
                rs.getObject("creditor_id", UUID.class), rs.getObject("portfolio_id", UUID.class),
                rs.getObject("debtor_id", UUID.class), rs.getString("external_id"),
                (Integer) rs.getObject("installment_number"), rs.getString("title_status"), rs.getString("situation"),
                rs.getObject("batch_id", UUID.class),
                rs.getTimestamp("occurred_at").toInstant(), rs.getBigDecimal("amount")));
    }

    @Override
    public Totals totals(Criteria criteria) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String source = source(criteria, params);
        if (source == null) {
            return new Totals(0, BigDecimal.ZERO);
        }
        return jdbc.queryForObject("select count(*) as total, coalesce(sum(amount), 0) as amount from (" + source
                + ") x", params, (rs, i) -> new Totals(rs.getLong("total"), rs.getBigDecimal("amount")));
    }

    /** As duas partes (zerado, suspenso) com os mesmos filtros; nulo com escopo vazio. */
    private static String source(Criteria c, MapSqlParameterSource params) {
        StringBuilder filter = new StringBuilder();
        if (c.allowedCreditors() != null) {
            if (c.allowedCreditors().isEmpty()) {
                return null;
            }
            filter.append(" and t.creditor_id in (:creditors)");
            params.addValue("creditors", c.allowedCreditors());
        }
        if (c.creditorId() != null) {
            filter.append(" and t.creditor_id = :creditorId");
            params.addValue("creditorId", c.creditorId());
        }
        params.addValue("from", Timestamp.from(c.from())).addValue("until", Timestamp.from(c.until()));
        String columns = """
                t.id as title_id, t.creditor_id, t.portfolio_id, t.debtor_id,
                coalesce(t.external_id_display, t.external_id) as external_id, t.installment_number,
                t.status as title_status""";
        List<String> parts = new ArrayList<>();
        boolean any = c.situation() == null;
        if (any || "SETTLED_BY_IMPORT".equals(c.situation())) {
            parts.add("select " + columns + """
                    , 'SETTLED_BY_IMPORT' as situation, t.settled_by_import_batch_id as batch_id,
                           t.settled_by_import_at as occurred_at,
                           coalesce((select abs(sum(e.amount)) from debt.title_ledger_entry e
                                      where e.title_id = t.id and e.entry_type = 'ADJUSTMENT'
                                        and e.reference_type = 'import_batch'
                                        and e.reference_id = t.settled_by_import_batch_id), 0) as amount
                      from debt.debt_title t
                     where t.settled_by_import_batch_id is not null and t.status = 'SETTLED'
                       and t.settled_by_import_at >= :from and t.settled_by_import_at < :until""" + filter
                    + batch(c, params, "t.settled_by_import_batch_id"));
        }
        if (any || "REPORTED_PAID".equals(c.situation()) || "REPORTED_CANCELLED".equals(c.situation())) {
            String code = c.situation() == null ? "" : " and t.suspension_reason_code = :code";
            if (c.situation() != null) {
                params.addValue("code", "REPORTED_PAID".equals(c.situation()) ? "CREDITOR_REPORTED_PAID"
                        : "CREDITOR_REPORTED_CANCELLED");
            }
            parts.add("select " + columns + """
                    , case t.suspension_reason_code when 'CREDITOR_REPORTED_PAID' then 'REPORTED_PAID'
                                                    else 'REPORTED_CANCELLED' end as situation,
                           t.suspension_import_batch_id as batch_id, t.suspended_at as occurred_at,
                           t.open_balance as amount
                      from debt.debt_title t
                     where t.suspension_reason_code is not null and t.status = 'SUSPENDED'
                       and t.suspended_at >= :from and t.suspended_at < :until""" + filter + code
                    + batch(c, params, "t.suspension_import_batch_id"));
        }
        return String.join(" union all ", parts);
    }

    private static String batch(Criteria c, MapSqlParameterSource params, String column) {
        if (c.importBatchId() == null) {
            return "";
        }
        params.addValue("importBatchId", c.importBatchId());
        return " and " + column + " = :importBatchId";
    }
}
