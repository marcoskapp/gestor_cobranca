package br.com.gestorcobranca;

import br.com.gestorcobranca.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** O modo {@code --app.mode=migrate} sobe só o Flyway, aplica as migrations e encerra com sucesso. */
class MigrationRunnerIT extends IntegrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void runsMigrationsOnlyAndExitsSuccessfully() {
        String[] args = {
                MigrationRunner.MODE_ARGUMENT,
                "--spring.profiles.active=test",
                "--spring.flyway.url=" + jdbcUrl(),
                "--spring.flyway.user=app_owner",
                "--spring.flyway.password=" + ownerPassword(),
                "--spring.datasource.url=" + jdbcUrl()
        };
        assertThat(MigrationRunner.isRequested(args)).isTrue();
        assertThat(MigrationRunner.run(args)).isZero();

        Integer failed = jdbcTemplate.queryForObject(
                "select count(*) from shared.flyway_schema_history where not success", Integer.class);
        assertThat(failed).isZero();
    }
}
