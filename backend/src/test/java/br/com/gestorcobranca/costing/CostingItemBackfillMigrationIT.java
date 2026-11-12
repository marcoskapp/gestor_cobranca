package br.com.gestorcobranca.costing;

import br.com.gestorcobranca.support.IntegrationTest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V2501 sobre um banco com fatos da fase 1: o preenchimento de {@code cost_daily.item} roda sob a RLS forçada (percorre
 * as assessorias com o tenant da transação) e o {@code check} novo recusa telefonia ou IA sem item — antes, o update
 * não enxergava nenhuma linha e o check aceitava o nulo, e a fatura contava a estimativa antiga duas vezes.
 */
class CostingItemBackfillMigrationIT extends IntegrationTest {

    @Test
    void preencheOItemDosFatosAntigosEmCadaAssessoriaERecusaTelefoniaSemItem() throws SQLException {
        String url = newEmptyDatabase();
        flyway(url, "2500").migrate();
        UUID tenant = UUID.randomUUID();
        try (Connection c = DriverManager.getConnection(url, "app_owner", ownerPassword());
             Statement st = c.createStatement()) {
            insertTenant(st, tenant);
            st.execute("select set_config('app.tenant_id', '" + tenant + "', false)");
            for (String category : List.of("TELEPHONY", "AI", "MESSAGING", "HUMAN_DIRECT")) {
                st.execute("""
                        insert into costing.cost_daily (tenant_id, day, category, amount, quantity, unit, basis)
                        values ('%s', date '2026-09-10', '%s', 1, 1, 'BRL', 'ESTIMATED')""".formatted(tenant, category));
            }
        }

        flyway(url, null).migrate();

        try (Connection c = DriverManager.getConnection(url, "app_owner", ownerPassword());
             Statement st = c.createStatement()) {
            st.execute("select set_config('app.tenant_id', '" + tenant + "', false)");
            List<String> rows = new ArrayList<>();
            try (ResultSet rs = st.executeQuery(
                    "select category || ':' || coalesce(item, '-') from costing.cost_daily order by category")) {
                while (rs.next()) {
                    rows.add(rs.getString(1));
                }
            }
            assertThat(rows).containsExactly("AI:AI", "HUMAN_DIRECT:-", "MESSAGING:-", "TELEPHONY:TELEPHONY");
            assertThatThrownBy(() -> st.execute("""
                    insert into costing.cost_daily (tenant_id, day, category, item, amount, quantity, unit, basis)
                    values ('%s', date '2026-09-11', 'TELEPHONY', null, 1, 1, 'BRL', 'ESTIMATED')""".formatted(tenant)))
                    .hasMessageContaining("ck_cost_daily_item");
        }
    }

    private static Flyway flyway(String url, String target) {
        var config = Flyway.configure().dataSource(url, "app_owner", ownerPassword())
                .locations("classpath:db/migration").defaultSchema("shared").createSchemas(true).outOfOrder(true);
        if (target != null) {
            config.target(target);
        }
        return config.load();
    }

    /** Assessoria mínima: as colunas obrigatórias sem padrão recebem um valor do tipo (o teste só precisa da chave). */
    private static void insertTenant(Statement st, UUID id) throws SQLException {
        List<String> columns = new ArrayList<>();
        List<String> values = new ArrayList<>();
        try (ResultSet rs = st.getConnection().createStatement().executeQuery("""
                select column_name, data_type from information_schema.columns
                 where table_schema = 'organization' and table_name = 'tenant' and is_nullable = 'NO'
                   and column_default is null order by ordinal_position""")) {
            while (rs.next()) {
                String column = rs.getString(1);
                columns.add(column);
                values.add(switch (column) {
                    case "id" -> "'" + id + "'";
                    case "status" -> "'ACTIVE'";
                    case "cnpj" -> "'11444777000161'";
                    case "contact_window_start" -> "time '08:00'";
                    case "contact_window_end" -> "time '20:00'";
                    case "slug" -> "'migr-" + id.toString().substring(0, 8) + "'";
                    default -> switch (rs.getString(2)) {
                        case "uuid" -> "gen_random_uuid()";
                        case "bigint", "integer", "smallint", "numeric" -> "0";
                        case "boolean" -> "false";
                        case "date" -> "current_date";
                        case "time without time zone" -> "time '08:00'";
                        case "timestamp with time zone" -> "now()";
                        default -> "'Teste'";
                    };
                });
            }
        }
        st.execute("insert into organization.tenant (" + String.join(", ", columns) + ") values ("
                + String.join(", ", values) + ")");
    }
}
