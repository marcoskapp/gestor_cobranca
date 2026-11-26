package br.com.gestorcobranca.creditorportal;

import br.com.gestorcobranca.identity.application.port.in.TenantProvisioning;
import br.com.gestorcobranca.identity.application.port.in.TenantProvisioning.ProvisionTenantCommand;
import br.com.gestorcobranca.identity.domain.model.creditor.CreditorRole;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases.CreditorCommand;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.ActorAuthentication;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import br.com.gestorcobranca.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Limite das leituras do Início do portal do credor pela API (fatia 7): 30 consultas de {@code /overview} por pessoa e
 * minuto; a 31ª recebe 429 com {@code Retry-After}, e o log de acesso {@code RATE_LIMITED} sai uma vez por janela.
 */
class CreditorReadLimitIT extends IntegrationTest {

    @Autowired
    TenantProvisioning provisioning;
    @Autowired
    TenantExecution tenantExecution;
    @Autowired
    CreditorUseCases creditors;
    @Autowired
    WebApplicationContext context;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    Clock clock;

    @Test
    void trigesimaPrimeiraConsultaDoInicioNoMesmoMinutoRecebe429ComRetryAfter() throws Exception {
        String slug = "rlim-" + UUID.randomUUID().toString().substring(0, 8);
        UUID tenant = tenantExecution.callInTenant(Ids.newId(), "test.setup", () -> provisioning.provision(
                new ProvisionTenantCommand(slug, "Assessoria Limite Ltda", "Limite", "11.444.777/0001-61",
                        "Admin", "admin@" + slug + ".test"))).tenant().id();
        UUID creditor = tenantExecution.callInTenant(tenant, "test", () -> creditors.create(
                new CreditorCommand("Banco Limite S.A.", "Banco Limite", "11.222.333/0001-81", null, null)).id());
        UUID person = Ids.newId();
        ActorAuthentication auth = ActorAuthentication.of(Actor.creditor(tenant, person, Ids.newId(), creditor,
                CreditorRole.LEITURA.permissions()));
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        // a janela é o minuto do relógio: começa longe da virada para as 31 consultas caírem na mesma janela
        long second = Instant.now(clock).getEpochSecond() % 60;
        if (second >= 45) {
            Thread.sleep((61 - second) * 1000L);
        }
        for (int i = 0; i < 30; i++) {
            mvc.perform(get("/api/creditor/v1/overview").param("period", "THIS_MONTH").with(authentication(auth)))
                    .andExpect(status().isOk());
        }
        for (int i = 0; i < 2; i++) {
            MvcResult rejected = mvc.perform(get("/api/creditor/v1/overview").param("period", "THIS_MONTH")
                    .with(authentication(auth))).andExpect(status().isTooManyRequests()).andReturn();
            assertThat(Integer.parseInt(rejected.getResponse().getHeader("Retry-After"))).isBetween(1, 60);
        }
        Long logged = tenantExecution.callInTenant(tenant, "test", () -> jdbcTemplate.queryForObject("""
                select count(*) from shared.access_log
                 where tenant_id = ? and actor_id = ? and event_type = 'RATE_LIMITED'
                   and details ->> 'scope' = 'creditor_read'""", Long.class, tenant, person));
        assertThat(logged).isEqualTo(1);
    }
}
