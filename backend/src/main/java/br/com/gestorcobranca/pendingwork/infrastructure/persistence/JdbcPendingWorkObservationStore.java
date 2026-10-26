package br.com.gestorcobranca.pendingwork.infrastructure.persistence;

import br.com.gestorcobranca.pendingwork.application.port.out.PendingWorkObservationStore;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Tabela {@code pendingwork.daily_observation} (RLS do tenant corrente; tenant pelo padrão da coluna). */
@Component
class JdbcPendingWorkObservationStore implements PendingWorkObservationStore {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcPendingWorkObservationStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean recordFirst(UUID userId, LocalDate day, String itemType, long total, long overdue,
                               Instant observedAt) {
        return jdbc.update("""
                insert into pendingwork.daily_observation (tenant_id, user_id, observed_on, item_type, total, overdue,
                                                           observed_at)
                values (shared.current_tenant_id(), :user, :day, :type, :total, :overdue, :at)
                on conflict (tenant_id, user_id, observed_on, item_type) do nothing
                """, new MapSqlParameterSource().addValue("user", userId).addValue("day", Date.valueOf(day))
                .addValue("type", itemType).addValue("total", total).addValue("overdue", overdue)
                .addValue("at", Timestamp.from(observedAt))) == 1;
    }

    @Override
    public int purgeBefore(LocalDate day) {
        return jdbc.update("delete from pendingwork.daily_observation where observed_on < :day",
                new MapSqlParameterSource("day", Date.valueOf(day)));
    }
}
