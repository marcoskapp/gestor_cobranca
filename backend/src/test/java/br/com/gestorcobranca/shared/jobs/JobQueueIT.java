package br.com.gestorcobranca.shared.jobs;

import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import br.com.gestorcobranca.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O {@code run_at} de um job imediato vem do relógio do banco, o mesmo do claim ({@code run_at <= now()}):
 * com a hora da aplicação, uma diferença de relógio entre aplicação e banco deixaria o job recém-criado
 * "no futuro" — fora do claim e do {@link JobDrain} — até os relógios se alcançarem.
 */
class JobQueueIT extends IntegrationTest {

    @Autowired
    JobQueue jobQueue;
    @Autowired
    TenantExecution tenantExecution;
    @Autowired
    JdbcTemplate jdbcTemplate;

    private final UUID tenant = Ids.newId();

    @Test
    void immediateJobUsesTheDatabaseClockAndScheduledJobKeepsItsInstant() {
        Map<String, Object> immediate = tenantExecution.callInTenant(tenant, "test", () -> {
            UUID id = jobQueue.enqueue("test.immediate", Map.of(), null, null);
            return jdbcTemplate.queryForMap("""
                    select run_at = now() as db_clock, run_at <= now() as ready
                      from shared.processing_job where id = ?""", id);
        });
        assertThat(immediate).containsEntry("db_clock", true).containsEntry("ready", true);

        Instant later = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
        Timestamp scheduled = tenantExecution.callInTenant(tenant, "test", () -> {
            UUID id = jobQueue.enqueue("test.scheduled", Map.of(), later, null);
            return jdbcTemplate.queryForObject("select run_at from shared.processing_job where id = ?",
                    Timestamp.class, id);
        });
        assertThat(scheduled.toInstant()).isEqualTo(later);
    }
}
