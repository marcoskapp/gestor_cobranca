package br.com.gestorcobranca.payment.infrastructure.persistence;

import br.com.gestorcobranca.payment.application.port.out.CreditorReportedPaymentsReader;
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
 * Pagamentos recebidos diretamente pelo credor, sob RLS (assessoria) e com o escopo por credor escrito na SQL
 * ({@code creditor_id in (:creditors)}), igual para as linhas e para os totais (RS-94). O token e o credor do filtro são
 * só condições a mais dentro desse escopo (RS-95). Índice {@code ix_payment_creditor_received} ({@code V0814}).
 */
@Component
class JdbcCreditorReportedPaymentsReader implements CreditorReportedPaymentsReader {

    /** Colunas aceitas na ordenação (o serviço já validou o nome público). */
    private static final Set<String> ORDER_COLUMNS = Set.of("paid_at", "amount", "id");

    private final NamedParameterJdbcTemplate jdbc;

    JdbcCreditorReportedPaymentsReader(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<UUID> page(Criteria criteria, List<Order> order, long offset, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(criteria, params);
        if (where == null) {
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
        return jdbc.query("select id from payment.payment" + where + " order by " + String.join(", ", orderBy)
                + " offset :offset limit :limit", params, (rs, i) -> rs.getObject("id", UUID.class));
    }

    @Override
    public Totals totals(Criteria criteria) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(criteria, params);
        if (where == null) {
            return new Totals(0, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        return jdbc.queryForObject("""
                select count(*) as total, coalesce(sum(amount), 0) as amount,
                       coalesce(sum(amount) filter (where status in ('CONFIRMED', 'RECONCILED')), 0) as effective
                  from payment.payment""" + where, params,
                (rs, i) -> new Totals(rs.getLong("total"), rs.getBigDecimal("amount"), rs.getBigDecimal("effective")));
    }

    /** Cláusula comum às linhas e aos totais; nulo quando o escopo é vazio (nada a ler). */
    private static String where(Criteria c, MapSqlParameterSource params) {
        StringBuilder sql = new StringBuilder(" " + """
                 where source = 'EXTERNAL'
                   and external_origin in ('CREDITOR_DIRECT', 'CREDITOR_REPORT')
                   and paid_at >= :paidFrom and paid_at < :paidUntil""");
        params.addValue("paidFrom", Timestamp.from(c.paidFrom())).addValue("paidUntil", Timestamp.from(c.paidUntil()));
        if (c.allowedCreditors() != null) {
            if (c.allowedCreditors().isEmpty()) {
                return null;
            }
            sql.append(" and creditor_id in (:creditors)");
            params.addValue("creditors", c.allowedCreditors());
        }
        if (c.creditorId() != null) {
            sql.append(" and creditor_id = :creditorId");
            params.addValue("creditorId", c.creditorId());
        }
        if (c.channel() != null) {
            sql.append(switch (c.channel()) {
                case "INTEGRATION" -> " and informed_channel = 'INTEGRATION'";
                case "CREDITOR_PORTAL" -> " and informed_channel = 'CREDITOR_PORTAL'";
                case "OPERATOR" -> " and informed_channel is null";
                default -> throw new IllegalArgumentException("Canal inválido: " + c.channel());
            });
        }
        if (c.tokenId() != null) {
            sql.append(" and informed_channel = 'INTEGRATION' and informed_by = :tokenId");
            params.addValue("tokenId", c.tokenId());
        }
        if (c.minAmount() != null) {
            sql.append(" and amount >= :minAmount");
            params.addValue("minAmount", c.minAmount());
        }
        if (c.maxAmount() != null) {
            sql.append(" and amount <= :maxAmount");
            params.addValue("maxAmount", c.maxAmount());
        }
        if (c.status() != null) {
            sql.append(" and status = :status");
            params.addValue("status", c.status());
        }
        return sql.toString();
    }
}
