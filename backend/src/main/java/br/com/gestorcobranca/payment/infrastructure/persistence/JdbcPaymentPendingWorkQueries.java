package br.com.gestorcobranca.payment.infrastructure.persistence;

import br.com.gestorcobranca.payment.application.port.out.PaymentPendingWorkQueries;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkCount;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkRows;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Índices: {@code ix_payment_review_queue} (status INFORMED) e {@code ix_divergence_open} (status OPEN).
 */
@Component
class JdbcPaymentPendingWorkQueries implements PaymentPendingWorkQueries {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcPaymentPendingWorkQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PendingWorkCount externalReview(Set<UUID> creditorScope, UUID reviewerId) {
        if (creditorScope != null && creditorScope.isEmpty()) {
            return PendingWorkCount.NONE;
        }
        MapSqlParameterSource params = new MapSqlParameterSource("me", reviewerId);
        String sql = """
                select count(*) as total, 0 as overdue, 0 as due_today, min(informed_at) as oldest_at,
                       null::date as next_due, case when count(*) = 1 then (array_agg(id))[1] end as single_id
                  from payment.payment
                 where status = 'INFORMED' and (informed_by is null or informed_by <> :me)
                """ + scope(creditorScope, params);
        return jdbc.queryForObject(sql, params, PendingWorkRows.COUNT);
    }

    @Override
    public Map<UUID, Long> externalReviewByAgreement(Set<UUID> creditorScope, UUID reviewerId) {
        if (creditorScope != null && creditorScope.isEmpty()) {
            return Map.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource("me", reviewerId);
        String sql = """
                select target_agreement_id, count(*) as total
                  from payment.payment
                 where status = 'INFORMED' and target_agreement_id is not null
                   and (informed_by is null or informed_by <> :me)
                """ + scope(creditorScope, params) + " group by target_agreement_id";
        Map<UUID, Long> result = new LinkedHashMap<>();
        jdbc.query(sql, params, rs -> {
            result.put(rs.getObject("target_agreement_id", UUID.class), rs.getLong("total"));
        });
        return result;
    }

    @Override
    public PendingWorkCount openDivergences(Set<UUID> creditorScope) {
        if (creditorScope != null && creditorScope.isEmpty()) {
            return PendingWorkCount.NONE;
        }
        MapSqlParameterSource params = new MapSqlParameterSource();
        String sql = """
                select count(*) as total, 0 as overdue, 0 as due_today, min(created_at) as oldest_at,
                       null::date as next_due, case when count(*) = 1 then (array_agg(id))[1] end as single_id
                  from payment.divergence
                 where status = 'OPEN'
                """ + scope(creditorScope, params);
        return jdbc.queryForObject(sql, params, PendingWorkRows.COUNT);
    }

    private static String scope(Set<UUID> creditorScope, MapSqlParameterSource params) {
        if (creditorScope == null) {
            return "";
        }
        params.addValue("scope", creditorScope);
        return " and creditor_id in (:scope)";
    }
}
