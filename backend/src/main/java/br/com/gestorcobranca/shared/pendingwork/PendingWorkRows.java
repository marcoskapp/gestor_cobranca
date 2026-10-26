package br.com.gestorcobranca.shared.pendingwork;

import br.com.gestorcobranca.shared.config.TimeConfig;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Leitura padronizada de uma linha de contagem de pendências pelos adaptadores JDBC dos módulos.
 * A consulta devolve as colunas {@code total}, {@code overdue}, {@code due_today}, {@code oldest_at}
 * ({@code timestamptz}), {@code next_due} ({@code date} ou {@code timestamptz}, convertido para a data
 * de negócio) e {@code single_id} ({@code uuid}, preenchido quando há um só item). Colunas de prazo
 * ausentes da regra podem vir como {@code 0}/{@code null}.
 * <p>
 * Para o id do único item, use {@code case when count(*) = 1 then (array_agg(id))[1] end as single_id}.
 */
public final class PendingWorkRows {

    public static final RowMapper<PendingWorkCount> COUNT = PendingWorkRows::map;

    private PendingWorkRows() {
    }

    private static PendingWorkCount map(ResultSet rs, int row) throws SQLException {
        long total = rs.getLong("total");
        if (total == 0) {
            return PendingWorkCount.NONE;
        }
        Timestamp oldest = rs.getTimestamp("oldest_at");
        return new PendingWorkCount(total, rs.getLong("overdue"), rs.getLong("due_today"),
                oldest == null ? null : oldest.toInstant(), date(rs.getObject("next_due")),
                rs.getObject("single_id", UUID.class));
    }

    private static LocalDate date(Object value) {
        if (value instanceof Date d) {
            return d.toLocalDate();
        }
        if (value instanceof Timestamp t) {
            return t.toInstant().atZone(TimeConfig.BUSINESS_ZONE).toLocalDate();
        }
        if (value instanceof java.time.OffsetDateTime o) {
            return o.atZoneSameInstant(TimeConfig.BUSINESS_ZONE).toLocalDate();
        }
        if (value instanceof Instant i) {
            return i.atZone(TimeConfig.BUSINESS_ZONE).toLocalDate();
        }
        if (value instanceof LocalDate l) {
            return l;
        }
        return null;
    }
}
