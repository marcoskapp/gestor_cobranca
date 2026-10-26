package br.com.gestorcobranca.legal.infrastructure.persistence;

import br.com.gestorcobranca.legal.application.port.out.LegalPendingWorkQueries;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkCount;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkRows;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/** Índice: {@code ix_legal_deadline_due (tenant_id, status, due_date)}; caso pela chave primária. */
@Component
class JdbcLegalPendingWorkQueries implements LegalPendingWorkQueries {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcLegalPendingWorkQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public PendingWorkCount deadlinesDue(Set<UUID> creditorScope, UUID lawFirmId, UUID userId, LocalDate today,
                                         LocalDate until) {
        if (creditorScope != null && creditorScope.isEmpty()) {
            return PendingWorkCount.NONE;
        }
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("me", userId)
                .addValue("today", Date.valueOf(today)).addValue("until", Date.valueOf(until));
        StringBuilder sql = new StringBuilder("""
                select count(*) as total,
                       count(*) filter (where d.due_date < :today) as overdue,
                       count(*) filter (where d.due_date = :today) as due_today,
                       min(d.created_at) as oldest_at, min(d.due_date) as next_due,
                       case when count(*) = 1 then (array_agg(d.matter_id))[1] end as single_id
                  from legal.legal_deadline d
                  join legal.legal_matter m on m.id = d.matter_id
                 where d.status = 'PENDING' and d.due_date <= :until
                """);
        if (lawFirmId == null) {
            sql.append(" and (d.responsible_user_id = :me or (d.responsible_user_id is null and m.responsible_user_id = :me))");
        } else {
            sql.append(" and m.law_firm_id = :firm and (d.responsible_user_id = :me or d.responsible_user_id is null)");
            params.addValue("firm", lawFirmId);
        }
        if (creditorScope != null) {
            sql.append(" and d.creditor_id in (:scope)");
            params.addValue("scope", creditorScope);
        }
        return jdbc.queryForObject(sql.toString(), params, PendingWorkRows.COUNT);
    }
}
