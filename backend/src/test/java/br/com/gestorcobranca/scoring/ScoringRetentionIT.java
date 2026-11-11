package br.com.gestorcobranca.scoring;

import br.com.gestorcobranca.compliance.application.port.in.RetentionUseCases;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration.CreateTenantCommand;
import br.com.gestorcobranca.scoring.application.service.ScoringTestHooks;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import br.com.gestorcobranca.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retenção dos fatos do score por assessoria contra PostgreSQL real: a assessoria que configurou o prazo na tela de
 * Retenção descarta pelo prazo dela; a outra, pelo padrão do sistema ({@code app.scoring.fact-retention-days}, 1095
 * dias) — e uma não enxerga nem altera a política nem os fatos da outra (RLS).
 */
class ScoringRetentionIT extends IntegrationTest {

    @Autowired
    TenantExecution tenantExecution;
    @Autowired
    TenantAdministration tenantAdministration;
    @Autowired
    RetentionUseCases retention;
    @Autowired
    ApplicationContext applicationContext;
    @Autowired
    JdbcTemplate jdbcTemplate;

    UUID configured;
    UUID byDefault;

    @BeforeEach
    void setUp() {
        configured = tenant("sret-a");
        byDefault = tenant("sret-b");
    }

    @Test
    void factsArePurgedByTheTenantPeriodOrTheSystemDefault() {
        for (UUID t : new UUID[] {configured, byDefault}) {
            fact(t, 400);
            fact(t, 800);
            fact(t, 1200);
        }
        in(configured, () -> retention.configure("SCORING_CASE_FACTS", 730, true));
        assertThat(in(configured, () -> retention.retentionFor("SCORING_CASE_FACTS")).orElseThrow())
                .satisfies(r -> {
                    assertThat(r.amount()).isEqualTo(730);
                    assertThat(r.source()).isEqualTo("TENANT");
                });
        assertThat(in(byDefault, () -> retention.retentionFor("SCORING_CASE_FACTS")).orElseThrow())
                .satisfies(r -> {
                    assertThat(r.amount()).isEqualTo(1095);
                    assertThat(r.source()).isEqualTo("SYSTEM_DEFAULT");
                });

        assertThat(in(configured, () -> ScoringTestHooks.purge(applicationContext))).isEqualTo(2);
        assertThat(in(byDefault, () -> ScoringTestHooks.purge(applicationContext))).isEqualTo(1);

        assertThat(facts(configured)).isEqualTo(1);
        assertThat(facts(byDefault)).isEqualTo(2);
        assertThat(in(byDefault, () -> jdbcTemplate.queryForObject(
                "select count(*) from compliance.retention_policy", Long.class))).isZero();
        // segunda execução: nada mais a descartar
        assertThat(in(configured, () -> ScoringTestHooks.purge(applicationContext))).isZero();
    }

    private void fact(UUID tenant, int ageDays) {
        in(tenant, () -> jdbcTemplate.update("""
                insert into scoring.case_fact (tenant_id, source_event_id, kind, debtor_id, occurred_at, effective_at)
                values (?, ?, 'CONTACT_ATTEMPT', ?, now() - make_interval(days => ?), now() - make_interval(days => ?))
                """, tenant, Ids.newId(), Ids.newId(), ageDays, ageDays));
    }

    private long facts(UUID tenant) {
        return in(tenant, () -> jdbcTemplate.queryForObject("select count(*) from scoring.case_fact where tenant_id = ?",
                Long.class, tenant));
    }

    private UUID tenant(String prefix) {
        String slug = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        return tenantExecution.callInTenant(Ids.newId(), "test.setup", () -> tenantAdministration
                .createTenant(new CreateTenantCommand(slug, "Assessoria Ltda", "Assessoria", "11.444.777/0001-61")).id());
    }

    private <T> T in(UUID tenant, Supplier<T> action) {
        return tenantExecution.callInTenant(tenant, "test", action);
    }
}
