package br.com.gestorcobranca.legal.application.service;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseFilter;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.legal.application.LegalProperties;
import br.com.gestorcobranca.legal.domain.model.Lawsuit;
import br.com.gestorcobranca.legal.domain.model.LawsuitStatus;
import br.com.gestorcobranca.legal.domain.model.LegalEligibilityRule;
import br.com.gestorcobranca.legal.domain.model.MatterOrigin;
import br.com.gestorcobranca.legal.domain.repository.LawsuitRepository;
import br.com.gestorcobranca.legal.domain.repository.LegalEligibilityRuleRepository;
import br.com.gestorcobranca.legal.domain.repository.LegalMatterRepository;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.tenancy.TenantDirectory;
import br.com.gestorcobranca.shared.tenancy.TenantExecution;
import io.micrometer.core.instrument.Timer;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Varredura diária do jurídico (uma instância por vez, via ShedLock), por assessoria ativa:
 * recálculo dos vencimentos (feriados e suspensões cadastrados depois), alertas e prazos perdidos,
 * indicação automática pelas regras e, se houver adaptador automático, captura de andamentos.
 * Cada caso/processo é tratado na própria transação; a falha numa assessoria é registrada
 * (ERROR + {@code legal.jobs.failures}) e não impede as demais.
 */
@Component
class LegalJobs {

    static final String JOB_DAILY = "legal_daily";
    private static final Logger LOG = LoggerFactory.getLogger(LegalJobs.class);
    private static final int CASE_PAGE = 200;

    private final TenantDirectory tenantDirectory;
    private final TenantExecution tenantExecution;
    private final LegalEligibilityRuleRepository rules;
    private final LegalMatterRepository matters;
    private final LawsuitRepository lawsuits;
    private final CaseUseCases cases;
    private final Referrals referrals;
    private final LegalDeadlineService deadlineService;
    private final DeadlineMonitor deadlineMonitor;
    private final LawsuitService lawsuitService;
    private final LegalProviders providers;
    private final LegalProperties properties;
    private final LegalMetrics metrics;

    LegalJobs(TenantDirectory tenantDirectory, TenantExecution tenantExecution, LegalEligibilityRuleRepository rules,
              LegalMatterRepository matters, LawsuitRepository lawsuits, CaseUseCases cases, Referrals referrals,
              LegalDeadlineService deadlineService, DeadlineMonitor deadlineMonitor, LawsuitService lawsuitService,
              LegalProviders providers, LegalProperties properties, LegalMetrics metrics) {
        this.tenantDirectory = tenantDirectory;
        this.tenantExecution = tenantExecution;
        this.rules = rules;
        this.matters = matters;
        this.lawsuits = lawsuits;
        this.cases = cases;
        this.referrals = referrals;
        this.deadlineService = deadlineService;
        this.deadlineMonitor = deadlineMonitor;
        this.lawsuitService = lawsuitService;
        this.providers = providers;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Scheduled(cron = "${app.legal.daily-cron:0 40 5 * * *}", zone = TimeConfig.BUSINESS_ZONE_ID)
    @SchedulerLock(name = "legal.daily", lockAtMostFor = "PT3H", lockAtLeastFor = "PT1M")
    void daily() {
        for (UUID tenantId : tenantDirectory.activeTenantIds()) {
            try {
                runDaily(tenantId);
            } catch (RuntimeException e) {
                metrics.jobFailure(JOB_DAILY);
                LOG.atError().addKeyValue("tenantId", tenantId).setCause(e).log("Falha na varredura diária do jurídico");
            }
        }
    }

    void runDaily(UUID tenantId) {
        Timer.Sample deadlinesTimer = metrics.startTimer();
        int rescheduled = tenantExecution.callInTenant(tenantId, "legal.deadlines_recompute",
                () -> deadlineService.recomputePending("daily"));
        UUID cursor = new UUID(0L, 0L);
        while (cursor != null) {
            UUID after = cursor;
            cursor = tenantExecution.callInTenant(tenantId, "legal.deadlines_scan",
                    () -> deadlineMonitor.scanBatch(after, properties.batchSize()));
        }
        metrics.stopScan(deadlinesTimer, "deadlines");

        Timer.Sample eligibilityTimer = metrics.startTimer();
        int referred = sweepEligibility(tenantId);
        metrics.stopScan(eligibilityTimer, "eligibility");

        int captured = 0;
        if (providers.courtData().automatic()) {
            Timer.Sample captureTimer = metrics.startTimer();
            captured = captureMovements(tenantId);
            metrics.stopScan(captureTimer, "capture");
        }
        LOG.atInfo().addKeyValue("tenantId", tenantId).addKeyValue("deadlinesRescheduled", rescheduled)
                .addKeyValue("referred", referred).addKeyValue("lawsuitsCaptured", captured)
                .log("Varredura diária do jurídico concluída");
    }

    /** Indicação automática: casos de cobrança ativos dos credores/carteiras com regras ativas. */
    int sweepEligibility(UUID tenantId) {
        List<LegalEligibilityRule> active = tenantExecution.callInTenant(tenantId, "legal.eligibility_rules",
                rules::findByActiveTrueOrderByCreatedAtAsc);
        Map<UUID, List<LegalEligibilityRule>> byCreditor = new LinkedHashMap<>();
        active.forEach(r -> byCreditor.computeIfAbsent(r.getCreditorId(), k -> new java.util.ArrayList<>()).add(r));
        int referred = 0;
        for (Map.Entry<UUID, List<LegalEligibilityRule>> entry : byCreditor.entrySet()) {
            int page = 0;
            List<CaseView> content;
            do {
                int current = page++;
                content = tenantExecution.callInTenant(tenantId, "legal.eligibility_cases", () -> cases.search(
                        new CaseFilter(entry.getKey(), null, null, Referrals.AUTOMATIC_STATUSES, null, null, null, null),
                        new PageQuery(current, CASE_PAGE)).content());
                for (CaseView c : content) {
                    if (referred >= properties.maxReferralsPerRun()) {
                        LOG.atWarn().addKeyValue("tenantId", tenantId).addKeyValue("limit", properties.maxReferralsPerRun())
                                .log("Limite de indicações automáticas por execução atingido");
                        return referred;
                    }
                    List<LegalEligibilityRule> applicable = entry.getValue().stream()
                            .filter(r -> r.appliesTo(c.creditorId(), c.portfolioId())).toList();
                    if (applicable.isEmpty()) {
                        continue;
                    }
                    try {
                        boolean created = tenantExecution.callInTenant(tenantId, "legal.eligibility_refer",
                                () -> referrals.referAutomatically(cases.get(c.id()), applicable, MatterOrigin.RULE)
                                        .isPresent());
                        if (created) {
                            referred++;
                        }
                    } catch (RuntimeException e) {
                        metrics.jobFailure("eligibility_case");
                        LOG.atWarn().addKeyValue("tenantId", tenantId).addKeyValue("caseId", c.id()).setCause(e)
                                .log("Falha ao avaliar a elegibilidade jurídica do caso");
                    }
                }
            } while (content.size() == CASE_PAGE);
        }
        return referred;
    }

    /** Captura de andamentos dos processos em andamento (somente com adaptador automático). */
    int captureMovements(UUID tenantId) {
        int processed = 0;
        UUID cursor = new UUID(0L, 0L);
        List<Lawsuit> batch;
        do {
            UUID after = cursor;
            batch = tenantExecution.callInTenant(tenantId, "legal.capture_batch", () ->
                    lawsuits.findByStatusAndIdGreaterThanOrderByIdAsc(LawsuitStatus.ACTIVE, after,
                            PageRequest.of(0, properties.batchSize())));
            for (Lawsuit l : batch) {
                cursor = l.getId();
                try {
                    tenantExecution.callInTenant(tenantId, "legal.capture", () -> {
                        var matter = matters.findByIdForUpdate(l.getMatterId()).orElseThrow();
                        if (matter.getStage().isTerminal()) {
                            return null;
                        }
                        return lawsuitService.capture(matter, lawsuits.findById(l.getId()).orElseThrow());
                    });
                    processed++;
                } catch (RuntimeException e) {
                    metrics.jobFailure("capture");
                    LOG.atWarn().addKeyValue("tenantId", tenantId).addKeyValue("lawsuitId", l.getId()).setCause(e)
                            .log("Falha na captura de andamentos do processo");
                }
            }
        } while (batch.size() == properties.batchSize());
        return processed;
    }
}
