package br.com.gestorcobranca.organization;

import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases.CreditorCommand;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases.CreditorView;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration.CreateTenantCommand;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Garante o isolamento entre assessorias em todas as camadas, inclusive quando o código
 * tenta acessar diretamente o banco com o tenant errado (RLS).
 */
class TenantIsolationIT extends IntegrationTest {

    @Autowired
    TenantExecution tenantExecution;
    @Autowired
    TenantAdministration tenantAdministration;
    @Autowired
    CreditorUseCases creditors;
    @Autowired
    JdbcTemplate jdbcTemplate;

    UUID tenantA;
    UUID tenantB;

    @BeforeEach
    void createTenants() {
        tenantA = createTenant("alfa");
        tenantB = createTenant("beta");
    }

    private UUID createTenant(String prefix) {
        String slug = prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
        return tenantExecution.callInTenant(Ids.newId(), "test.setup", () -> tenantAdministration
                .createTenant(new CreateTenantCommand(slug, prefix + " Ltda", prefix, "11.444.777/0001-61")).id());
    }

    @Test
    void dataOfOneTenantIsInvisibleToAnother() {
        CreditorView creditor = tenantExecution.callInTenant(tenantA, "test", () ->
                creditors.create(new CreditorCommand("Credor A S.A.", "Credor A", "11.222.333/0001-81", null, null)));

        long visibleToB = tenantExecution.callInTenant(tenantB, "test",
                () -> creditors.search(null, null, PageQuery.of(0, 50)).totalElements());
        assertThat(visibleToB).isZero();

        assertThatThrownBy(() -> tenantExecution.callInTenant(tenantB, "test", () -> creditors.get(creditor.id())))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(tenantExecution.callInTenant(tenantA, "test", () -> creditors.get(creditor.id())).id())
                .isEqualTo(creditor.id());
    }

    @Test
    void databaseRejectsWritesForAnotherTenantEvenBypassingTheApplication() {
        assertThatThrownBy(() -> tenantExecution.inTenant(tenantB, "test", () -> jdbcTemplate.update("""
                insert into organization.creditor (id, tenant_id, version, created_at, updated_at, legal_name,
                    trade_name, document, status)
                values (?, ?, 0, now(), now(), 'Intruso', 'Intruso', '11222333000181', 'ACTIVE')
                """, Ids.newId(), tenantA)))
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("row-level security");
    }

    @Test
    void withoutTenantContextNoRowIsVisible() {
        tenantExecution.inTenant(tenantA, "test", () ->
                creditors.create(new CreditorCommand("Credor X", null, "11.222.333/0001-81", null, null)));
        Long count = jdbcTemplate.queryForObject("select count(*) from organization.creditor", Long.class);
        assertThat(count).isZero();
    }

    @Test
    void auditTrailIsAppendOnly() {
        tenantExecution.inTenant(tenantA, "test", () ->
                creditors.create(new CreditorCommand("Credor Y", null, "11.222.333/0001-81", null, null)));
        assertThatThrownBy(() -> tenantExecution.inTenant(tenantA, "test",
                () -> jdbcTemplate.update("update shared.audit_event set reason = 'adulterado'")))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> tenantExecution.inTenant(tenantA, "test",
                () -> jdbcTemplate.update("delete from shared.audit_event")))
                .isInstanceOf(DataAccessException.class);
    }
}
