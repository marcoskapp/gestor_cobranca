package br.com.gestorcobranca.shared.privacy;

import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Leitura padronizada de uma linha de uso pelos adaptadores JDBC das fontes. A consulta devolve as colunas
 * {@code code} ({@code varchar}, pode ser nula), {@code recipient_id} ({@code uuid}, pode ser nula), {@code name},
 * {@code first_at} e {@code last_at} ({@code timestamptz}), {@code total} e {@code latest_id} ({@code uuid} do registro
 * mais recente, pode ser nula — use {@code (array_agg(id order by <data> desc))[1]}).
 */
public final class DataSharingRows {

    public static final RowMapper<DataSharingUsage> USAGE = DataSharingRows::map;

    private DataSharingRows() {
    }

    private static DataSharingUsage map(ResultSet rs, int row) throws SQLException {
        return new DataSharingUsage(rs.getString("code"), rs.getObject("recipient_id", UUID.class), rs.getString("name"),
                instant(rs.getTimestamp("first_at")), instant(rs.getTimestamp("last_at")), rs.getLong("total"),
                rs.getObject("latest_id", UUID.class));
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
