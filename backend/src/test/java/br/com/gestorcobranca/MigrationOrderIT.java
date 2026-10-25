package br.com.gestorcobranca;

import br.com.gestorcobranca.support.IntegrationTest;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Connection;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cada módulo tem a sua faixa de versões de migration. Um banco que já recebeu versões altas (ex.:
 * V18xx) precisa aceitar uma migration nova de faixa baixa (ex.: identity V02xx) na atualização —
 * situação de toda base existente quando um módulo de faixa baixa ganha migration.
 */
class MigrationOrderIT extends IntegrationTest {

    @Autowired
    Flyway flyway;

    @Test
    void aLowerVersionAddedAfterHigherOnesIsAppliedOnUpgrade() throws Exception {
        // conexão de migração (dona do schema), a mesma que o Flyway usa
        try (Connection c = flyway.getConfiguration().getDataSource().getConnection(); Statement s = c.createStatement()) {
            // simula uma base criada antes da V0207: a tabela e o registro da migration não existem
            s.execute("drop table identity.role_default_baseline");
            s.execute("delete from shared.flyway_schema_history where version = '0207'");
        }

        MigrateResult result = flyway.migrate();

        assertThat(result.migrationsExecuted).isEqualTo(1);
        assertThat(result.migrations).extracting(m -> m.version).containsExactly("0207");
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }
}
