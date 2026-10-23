package br.com.gestorcobranca.negotiation.application.service;

import br.com.gestorcobranca.negotiation.application.port.in.AuthorityProvisioning;
import br.com.gestorcobranca.shared.config.TimeConfig;
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
    private final NegotiationMaintenance maintenance;
    private final NegotiationMetrics metrics;

    NegotiationJobs(TenantDirectory tenants, TenantExecution tenantExecution, AuthorityProvisioning provisioning,
                    NegotiationMaintenance maintenance, NegotiationMetrics metrics) {
        this.tenants = tenants;
        this.tenantExecution = tenantExecution;
        this.provisioning = provisioning;
        this.maintenance = maintenance;
        this.metrics = metrics;
    }

    /**
     * Diário, logo após a meia-noite no fuso de operação: propostas vencidas → EXPIRED; parcelas
     * vencidas → OVERDUE; acordos que atingiram a regra de quebra → BROKEN.
     */
    @Scheduled(cron = "${app.negotiation.daily-cron:0 20 0 * * *}", zone = TimeConfig.BUSINESS_ZONE_ID)
    @SchedulerLock(name = "negotiation.daily", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    void daily() {
        for (UUID tenantId : tenants.activeTenantIds()) {
            runDaily(tenantId);
        }
    }

    /** Execução diária de um tenant (cada lote em transação própria; falha registrada sem afetar os demais). */
    void runDaily(UUID tenantId) {
        try {
            int expired;
            do {
                expired = tenantExecution.callInTenant(tenantId, "negotiation.proposal_expiry", maintenance::expireProposals);
            } while (expired == NegotiationMaintenance.BATCH);
        } catch (RuntimeException e) {
            failure("negotiation.proposal_expiry", tenantId, e);
        }
        try {
            UUID cursor = null;
            do {
                UUID after = cursor;
                cursor = tenantExecution.callInTenant(tenantId, "negotiation.agreement_sweep",
                        () -> maintenance.sweepAgreements(after));
            } while (cursor != null);
        } catch (RuntimeException e) {
            failure("negotiation.agreement_sweep", tenantId, e);
        }
    }

    private void failure(String job, UUID tenantId, RuntimeException e) {
        metrics.jobFailure(job);
        LOG.atError().addKeyValue("tenantId", tenantId).addKeyValue("job", job).setCause(e)
                .log("Falha em tarefa agendada do módulo negotiation");
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
