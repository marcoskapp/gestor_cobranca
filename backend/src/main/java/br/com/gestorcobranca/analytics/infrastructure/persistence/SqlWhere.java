package br.com.gestorcobranca.analytics.infrastructure.persistence;

import br.com.gestorcobranca.analytics.application.port.out.Slice;
import br.com.gestorcobranca.shared.config.TimeConfig;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;

/**
 * Monta a cláusula {@code where} de uma consulta a partir do recorte. Colunas sempre vêm do
 * código (nunca do cliente); valores sempre como parâmetros nomeados. Restrição {@code null} =
 * sem filtro; vazia = nada corresponde ({@code false}).
 */
final class SqlWhere {

    private final StringBuilder sql = new StringBuilder("true");
    private final MapSqlParameterSource params;

    SqlWhere(MapSqlParameterSource params) {
        this.params = params;
    }

    static MapSqlParameterSource params(Slice slice) {
        return new MapSqlParameterSource()
                .addValue("from", slice.from(), Types.DATE)
                .addValue("to", slice.to(), Types.DATE)
                .addValue("fromTs", ts(slice.fromInstant()), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("toTs", ts(slice.toInstant()), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("zone", TimeConfig.BUSINESS_ZONE_ID);
    }

    static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    SqlWhere dateBetween(String column) {
        sql.append(" and ").append(column).append(" between :from and :to");
        return this;
    }

    SqlWhere instantIn(String column) {
        sql.append(" and ").append(column).append(" >= :fromTs and ").append(column).append(" < :toTs");
        return this;
    }

    SqlWhere in(String column, Collection<?> values) {
        if (values == null) {
            return this;
        }
        if (values.isEmpty()) {
            sql.append(" and false");
            return this;
        }
        // nome único na fonte de parâmetros, que pode ser compartilhada por várias cláusulas
        String name = "w" + params.getValues().size();
        sql.append(" and ").append(column).append(" in (:").append(name).append(')');
        params.addValue(name, values);
        return this;
    }

    SqlWhere raw(String condition) {
        sql.append(" and ").append(condition);
        return this;
    }

    SqlWhere date(String name, LocalDate value) {
        params.addValue(name, value, Types.DATE);
        return this;
    }

    /** Fatos sem credor só entram sem restrição de credores. */
    SqlWhere creditors(String column, Slice slice) {
        return in(column, slice.creditors());
    }

    @Override
    public String toString() {
        return sql.toString();
    }
}
