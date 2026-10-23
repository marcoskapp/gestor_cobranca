package br.com.gestorcobranca.negotiation.application.service;

import br.com.gestorcobranca.negotiation.application.port.in.AuthorityProvisioning;
import br.com.gestorcobranca.shared.tenancy.TenantDirectory;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Agendamentos do módulo (uma instância por vez, via ShedLock), por assessoria ativa; falha num
 * tenant é registrada e não impede os demais.
 */
@Component
class NegotiationJobs {

    private static final Logger LOG = LoggerFactory.getLogger(NegotiationJobs.class);
    private static final String PROCESS = "negotiation.default_profiles";

    private final TenantDirectory tenants;
    private final TenantExecution tenantExecution;
    private final AuthorityProvisioning provisioning;
    private final NegotiationMetrics metrics;

    NegotiationJobs(TenantDirectory tenants, TenantExecution tenantExecution, AuthorityProvisioning provisioning,
                    NegotiationMetrics metrics) {
        this.tenants = tenants;
        this.tenantExecution = tenantExecution;
        this.provisioning = provisioning;
        this.metrics = metrics;
    }

    /** Assessorias anteriores ao módulo (ou com perfil padrão ausente) recebem os perfis de alçada padrão. */
    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "P1D")
    @SchedulerLock(name = "negotiation.default-profiles", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    void ensureDefaultProfiles() {
        int seededTenants = 0;
        for (UUID tenantId : tenants.activeTenantIds()) {
            try {
                Integer created = tenantExecution.callInTenant(tenantId, PROCESS, provisioning::ensureDefaultProfiles);
                if (created != null && created > 0) {
                    seededTenants++;
                }
            } catch (RuntimeException e) {
                metrics.jobFailure(PROCESS);
                LOG.atError().addKeyValue("tenantId", tenantId).addKeyValue("process", PROCESS).setCause(e)
                        .log("Falha ao semear perfis de alçada padrão");
            }
        }
        if (seededTenants > 0) {
            LOG.atInfo().addKeyValue("process", PROCESS).addKeyValue("tenants", seededTenants)
                    .log("Perfis de alçada padrão semeados em assessorias existentes");
        }
    }
}
