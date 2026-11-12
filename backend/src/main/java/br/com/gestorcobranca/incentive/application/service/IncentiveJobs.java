package br.com.gestorcobranca.incentive.application.service;

import br.com.gestorcobranca.incentive.application.IncentiveProperties;
import br.com.gestorcobranca.incentive.application.port.in.IncentiveProvisioning;
import br.com.gestorcobranca.incentive.application.port.in.TierScheduling;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.tenancy.TenantDirectory;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Agendamentos do módulo (uma instância por vez, via ShedLock), por assessoria ativa; falha num
 * tenant é registrada e não impede os demais.
 */
@Component
class IncentiveJobs {

    private static final Logger LOG = LoggerFactory.getLogger(IncentiveJobs.class);

    private final TenantDirectory tenants;
    private final TenantExecution tenantExecution;
    private final ClearingService clearing;
    private final PeriodClosingService closing;
    private final CommissionPostingService posting;
    private final LoggedTimeSync loggedTime;
    private final IncentiveProvisioning provisioning;
    private final TierScheduling tiers;
    private final IncentiveMetrics metrics;
    private final IncentiveProperties properties;
    private final Clock clock;

    IncentiveJobs(TenantDirectory tenants, TenantExecution tenantExecution, ClearingService clearing,
                  PeriodClosingService closing, CommissionPostingService posting, LoggedTimeSync loggedTime,
                  IncentiveProvisioning provisioning, TierScheduling tiers, IncentiveMetrics metrics,
                  IncentiveProperties properties, Clock clock) {
        this.tenants = tenants;
        this.tenantExecution = tenantExecution;
        this.clearing = clearing;
        this.closing = closing;
        this.posting = posting;
        this.loggedTime = loggedTime;
        this.provisioning = provisioning;
        this.tiers = tiers;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    /** Liberação de carência e fechamento automático dos períodos vencidos (madrugada, fuso de operação). */
    @Scheduled(cron = "${app.incentive.daily-cron:0 20 3 * * *}", zone = TimeConfig.BUSINESS_ZONE_ID)
    @SchedulerLock(name = "incentive.daily", lockAtMostFor = "PT2H", lockAtLeastFor = "PT1M")
    void daily() {
        LocalDate today = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
        forEachTenant("incentive.clearing", () -> clearing.clearDue(today));
        if (properties.autoClose()) {
            forEachTenant("incentive.period_close", closing::closeDuePeriods);
        }
    }

    /**
     * Classificação de desempenho (distribuicao-por-desempenho.md §3.3): expira a rodada sem publicação no prazo e
     * abre a rodada devida no período (mensal a partir do 1º dia útil; semanal a partir da segunda), de manhã no fuso
     * de operação. As duas etapas rodam em transações separadas: a expiração vale mesmo se a abertura falhar.
     */
    @Scheduled(cron = "${app.incentive.tier-cron:0 40 5 * * *}", zone = TimeConfig.BUSINESS_ZONE_ID)
    @SchedulerLock(name = "incentive.tier-rounds", lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    void tierRounds() {
        forEachTenant("incentive.tier_rounds", () -> {
            tiers.expireOverdueRound();
            return null;
        });
        forEachTenant("incentive.tier_rounds", () -> {
            tiers.openScheduledRound();
            return null;
        });
    }

    @Scheduled(fixedDelayString = "${app.incentive.logged-time-sync-interval:PT1H}", initialDelayString = "PT5M")
    @SchedulerLock(name = "incentive.logged-time", lockAtMostFor = "PT30M", lockAtLeastFor = "PT30S")
    void syncLoggedTime() {
        forEachTenant("incentive.logged_time", loggedTime::syncRecent);
    }

    /** Contingência: pagamentos cuja atribuição chegou sem disparar o lançamento (ex.: reprocessamento). */
    @Scheduled(fixedDelayString = "${app.incentive.posting-sweep-interval:PT15M}", initialDelayString = "PT4M")
    @SchedulerLock(name = "incentive.posting-sweep", lockAtMostFor = "PT10M", lockAtLeastFor = "PT10S")
    void sweepPostings() {
        Instant before = Instant.now(clock).minus(properties.postingSweepMinAge());
        forEachTenant("incentive.posting_sweep", () -> posting.sweep(before, 200));
    }

    /** Assessorias anteriores ao módulo recebem o plano padrão (idempotente). */
    @Scheduled(initialDelayString = "PT2M", fixedDelayString = "P1D")
    @SchedulerLock(name = "incentive.default-plan", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    void ensureDefaultPlans() {
        forEachTenant("incentive.default_plan", provisioning::ensureDefaultPlan);
    }

    private void forEachTenant(String process, java.util.function.Supplier<?> action) {
        for (UUID tenantId : tenants.activeTenantIds()) {
            try {
                tenantExecution.callInTenant(tenantId, process, action);
            } catch (RuntimeException e) {
                metrics.jobFailure(process);
                LOG.atError().addKeyValue("tenantId", tenantId).addKeyValue("process", process).setCause(e)
                        .log("Falha em processamento agendado do módulo incentive");
            }
        }
    }
}
