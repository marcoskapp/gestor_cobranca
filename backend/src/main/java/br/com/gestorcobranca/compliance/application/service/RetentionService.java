package br.com.gestorcobranca.compliance.application.service;

import br.com.gestorcobranca.compliance.application.ComplianceProperties;
import br.com.gestorcobranca.compliance.application.port.in.RetentionUseCases;
import br.com.gestorcobranca.compliance.domain.model.LegalBasisRecord;
import br.com.gestorcobranca.compliance.domain.model.RetentionCategory;
import br.com.gestorcobranca.compliance.domain.model.RetentionPolicy;
import br.com.gestorcobranca.compliance.domain.model.RetentionRun;
import br.com.gestorcobranca.compliance.domain.model.RetentionRunItem;
import br.com.gestorcobranca.compliance.domain.model.RetentionRunItem.Outcome;
import br.com.gestorcobranca.compliance.domain.repository.LegalBasisRecordRepository;
import br.com.gestorcobranca.compliance.domain.repository.RetentionPolicyRepository;
import br.com.gestorcobranca.compliance.domain.repository.RetentionRunItemRepository;
import br.com.gestorcobranca.compliance.domain.repository.RetentionRunRepository;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases.InactiveDebtor;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.DomainException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Permission;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Retenção e descarte. A execução de DEBTOR_INACTIVE não é uma transação única: cada
 * anonimização ocorre em transação própria (uma falha não desfaz as demais e o progresso fica
 * registrado), e a execução ({@link RetentionRun}) é atualizada lote a lote. Por isso os
 * métodos de execução são {@code NOT_SUPPORTED} e controlam as transações explicitamente.
 * <p>
 * Prazo efetivo de cada devedor: o maior entre o da política e o exigido pelas bases legais
 * registradas para os credores do devedor; quem ainda está dentro do prazo maior é mantido
 * ({@code RETAINED}) e registrado como tal.
 */
@Service
@Transactional
class RetentionService implements RetentionUseCases {

    private static final Logger LOG = LoggerFactory.getLogger(RetentionService.class);
    private static final String RESOURCE = "retention_policy";

    private final RetentionPolicyRepository policies;
    private final RetentionRunRepository runs;
    private final RetentionRunItemRepository items;
    private final LegalBasisRecordRepository legalBases;
    private final DebtorUseCases debtors;
    private final AccessScope accessScope;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final MeterRegistry meterRegistry;
    private final ComplianceProperties properties;
    private final TransactionTemplate newTransaction;
    private final Clock clock;

    RetentionService(RetentionPolicyRepository policies, RetentionRunRepository runs, RetentionRunItemRepository items,
                     LegalBasisRecordRepository legalBases, DebtorUseCases debtors, AccessScope accessScope,
                     AuditTrail auditTrail, BusinessEventLogger businessEvents, MeterRegistry meterRegistry,
                     ComplianceProperties properties, PlatformTransactionManager transactionManager, Clock clock) {
        this.policies = policies;
        this.runs = runs;
        this.items = items;
        this.legalBases = legalBases;
        this.debtors = debtors;
        this.accessScope = accessScope;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.meterRegistry = meterRegistry;
        this.properties = properties;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RetentionPolicyView> listPolicies() {
        accessScope.requireAllCreditors(RESOURCE);
        return policies.findAllByOrderByCategoryAsc().stream().map(RetentionService::view).toList();
    }

    @Override
    public RetentionPolicyView configure(String category, int retentionMonths, boolean enabled) {
        accessScope.requireAllCreditors(RESOURCE);
        RetentionCategory parsed = ComplianceEnums.parse(RetentionCategory.class, category, "retention.category_invalid",
                "Categoria");
        Optional<RetentionPolicy> existing = policies.findByCategory(parsed);
        RetentionPolicy policy = existing.orElseGet(() -> new RetentionPolicy(parsed, retentionMonths));
        Integer previousMonths = existing.map(RetentionPolicy::getRetentionMonths).orElse(null);
        Boolean previousEnabled = existing.map(RetentionPolicy::isEnabled).orElse(null);
        policy.changeRetention(retentionMonths);
        if (enabled) {
            policy.enable();
        } else {
            policy.disable();
        }
        policies.save(policy);
        auditTrail.record(AuditEntry.of(existing.isPresent() ? "retention_policy.updated" : "retention_policy.created",
                        RESOURCE, policy.getId())
                .change("category", existing.isPresent() ? parsed : null, parsed)
                .change("retentionMonths", previousMonths, policy.getRetentionMonths())
                .change("enabled", previousEnabled, policy.isEnabled()));
        return view(policy);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RetentionPolicyView> policyFor(String category) {
        RetentionCategory parsed = ComplianceEnums.parse(RetentionCategory.class, category, "retention.category_invalid",
                "Categoria");
        return policies.findByCategory(parsed).map(RetentionService::view);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RetentionRunView run(String category, boolean dryRun) {
        accessScope.requireAllCreditors(RESOURCE);
        RetentionCategory parsed = ComplianceEnums.parse(RetentionCategory.class, category, "retention.category_invalid",
                "Categoria");
        Guard.requireState(parsed.isExecutableHere(), "retention.category_not_executable",
                "Categoria %s é descartada pelo módulo dono dos dados".formatted(parsed));
        RetentionPolicy policy = newTransaction.execute(s -> policies.findByCategory(parsed))
                .orElseThrow(() -> new ConflictException("retention.policy_not_configured",
                        "Configure a política de retenção da categoria antes de executar"));
        if (!dryRun) {
            Guard.requireState(policy.isEnabled(), "retention.policy_disabled",
                    "Política desabilitada: habilite-a ou execute uma simulação");
            accessScope.requirePermission(Permission.DATA_SUBJECT_REQUEST_MANAGE);
        }
        return view(execute(policy, RetentionRun.Trigger.MANUAL, dryRun));
    }

    /**
     * Execução agendada no tenant do contexto (ator de sistema): somente categorias executáveis,
     * habilitadas. Chamada sem transação ativa.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void runScheduled() {
        Optional<RetentionPolicy> policy = newTransaction.execute(s -> policies.findByCategory(RetentionCategory.DEBTOR_INACTIVE));
        if (policy.isPresent() && policy.get().isEnabled()) {
            execute(policy.get(), RetentionRun.Trigger.SCHEDULED, false);
        }
    }

    private RetentionRun execute(RetentionPolicy policy, RetentionRun.Trigger trigger, boolean dryRun) {
        Instant now = Instant.now(clock);
        Instant cutoff = ZonedDateTime.ofInstant(now, TimeConfig.BUSINESS_ZONE).minusMonths(policy.getRetentionMonths())
                .toInstant();
        // transações separadas: o índice único de execução em andamento precisa ver a anterior já encerrada
        newTransaction.executeWithoutResult(s -> releaseStaleRun(policy.getCategory(), now));
        RetentionRun run = newTransaction.execute(s -> start(policy, trigger, dryRun, cutoff, now));
        try {
            UUID afterId = null;
            List<InactiveDebtor> batch;
            do {
                UUID from = afterId;
                batch = newTransaction.execute(s -> debtors.findInactiveDebtors(cutoff, from,
                        properties.retention().batchSize()));
                if (!batch.isEmpty()) {
                    afterId = batch.getLast().debtorId();
                    processBatch(run, batch, dryRun, now);
                }
            } while (batch.size() == properties.retention().batchSize());
            RetentionRun finished = newTransaction.execute(s -> finish(run.getId()));
            meterRegistry.counter("compliance.retention.runs", "category", policy.getCategory().name(),
                    "dryRun", String.valueOf(dryRun), "outcome", "completed").increment();
            LOG.atInfo().addKeyValue("runId", run.getId()).addKeyValue("category", policy.getCategory().name())
                    .addKeyValue("dryRun", dryRun).addKeyValue("eligible", finished.getEligibleCount())
                    .addKeyValue("processed", finished.getProcessedCount())
                    .addKeyValue("failed", finished.getFailedCount())
                    .log("Execução de retenção concluída");
            return finished;
        } catch (RuntimeException e) {
            newTransaction.executeWithoutResult(s -> runs.findById(run.getId()).ifPresent(r -> {
                r.fail(e.getClass().getSimpleName() + ": " + e.getMessage(), Instant.now(clock));
                runs.save(r);
            }));
            meterRegistry.counter("compliance.retention.runs", "category", policy.getCategory().name(),
                    "dryRun", String.valueOf(dryRun), "outcome", "failed").increment();
            LOG.atError().addKeyValue("runId", run.getId()).setCause(e).log("Execução de retenção interrompida por falha");
            throw e;
        }
    }

    /** Execução em andamento há mais tempo que o limite foi interrompida (instância encerrada no meio). */
    private void releaseStaleRun(RetentionCategory category, Instant now) {
        runs.findByCategoryAndStatus(category, RetentionRun.Status.RUNNING).ifPresent(previous -> {
            if (previous.getStartedAt().plus(properties.retention().staleRunAfter()).isAfter(now)) {
                throw new ConflictException("retention.run_in_progress", "Já existe execução de retenção em andamento");
            }
            previous.fail("Execução interrompida (instância encerrada durante o processamento)", now);
            runs.save(previous);
            LOG.atWarn().addKeyValue("runId", previous.getId()).log("Execução de retenção abandonada marcada como falha");
        });
    }

    private RetentionRun start(RetentionPolicy policy, RetentionRun.Trigger trigger, boolean dryRun, Instant cutoff,
                               Instant now) {
        RetentionRun run = runs.save(new RetentionRun(policy.getCategory(), trigger, dryRun, policy.getRetentionMonths(),
                cutoff, now));
        auditTrail.record(AuditEntry.of("retention_run.started", "retention_run", run.getId())
                .created("category", run.getCategory())
                .created("dryRun", dryRun)
                .created("trigger", trigger)
                .created("retentionMonths", run.getRetentionMonths())
                .created("cutoff", cutoff));
        return run;
    }

    private void processBatch(RetentionRun run, List<InactiveDebtor> batch, boolean dryRun, Instant now) {
        Map<UUID, Integer> retentionByCreditor = newTransaction.execute(s -> maxRetentionByCreditor(batch));
        int eligible = 0;
        int processed = 0;
        int retained = 0;
        int failed = 0;
        for (InactiveDebtor debtor : batch) {
            int required = debtor.creditorIds().stream().mapToInt(c -> retentionByCreditor.getOrDefault(c, 0)).max().orElse(0);
            if (required > run.getRetentionMonths()
                    && ZonedDateTime.ofInstant(debtor.lastActivityAt(), TimeConfig.BUSINESS_ZONE).plusMonths(required)
                    .toInstant().isAfter(now)) {
                retained++;
                saveItem(run, debtor.debtorId(), Outcome.RETAINED, null,
                        "Base legal do credor exige retenção de %d meses".formatted(required));
                continue;
            }
            eligible++;
            if (dryRun) {
                saveItem(run, debtor.debtorId(), Outcome.ELIGIBLE, null, null);
                continue;
            }
            try {
                newTransaction.executeWithoutResult(s -> {
                    debtors.anonymize(debtor.debtorId(), "Política de retenção: sem movimentação há %d meses (execução %s)"
                            .formatted(run.getRetentionMonths(), run.getId()));
                    items.save(new RetentionRunItem(run.getId(), debtor.debtorId(), Outcome.ANONYMIZED, null, null,
                            Instant.now(clock)));
                });
                processed++;
            } catch (DomainException e) {
                failed++;
                saveItem(run, debtor.debtorId(), Outcome.FAILED, e.code(), e.getMessage());
            } catch (RuntimeException e) {
                failed++;
                LOG.atWarn().addKeyValue("runId", run.getId()).addKeyValue("debtorId", debtor.debtorId()).setCause(e)
                        .log("Falha ao anonimizar devedor na retenção");
                saveItem(run, debtor.debtorId(), Outcome.FAILED, "retention.unexpected_error", e.getClass().getSimpleName());
            }
        }
        int candidates = batch.size();
        int e = eligible;
        int p = processed;
        int r = retained;
        int f = failed;
        newTransaction.executeWithoutResult(s -> {
            RetentionRun current = runs.findById(run.getId()).orElseThrow();
            current.record(candidates, e, p, r, f);
            runs.save(current);
        });
        meterRegistry.counter("compliance.retention.debtors", "outcome", "anonymized").increment(p);
        meterRegistry.counter("compliance.retention.debtors", "outcome", "failed").increment(f);
    }

    private Map<UUID, Integer> maxRetentionByCreditor(Collection<InactiveDebtor> batch) {
        List<UUID> creditorIds = batch.stream().flatMap(d -> d.creditorIds().stream()).distinct().toList();
        if (creditorIds.isEmpty()) {
            return Map.of();
        }
        return legalBases.findByCreditorIdIn(creditorIds).stream()
                .collect(Collectors.toMap(LegalBasisRecord::getCreditorId, LegalBasisRecord::getRetentionMonths, Math::max));
    }

    private void saveItem(RetentionRun run, UUID debtorId, Outcome outcome, String errorCode, String detail) {
        newTransaction.executeWithoutResult(s -> items.save(new RetentionRunItem(run.getId(), debtorId, outcome, errorCode,
                detail, Instant.now(clock))));
    }

    private RetentionRun finish(UUID runId) {
        RetentionRun run = runs.findById(runId).orElseThrow();
        run.complete(Instant.now(clock));
        runs.save(run);
        auditTrail.record(AuditEntry.of("retention_run.completed", "retention_run", runId)
                .change("status", RetentionRun.Status.RUNNING, run.getStatus())
                .meta("candidates", run.getCandidateCount())
                .meta("eligible", run.getEligibleCount())
                .meta("processed", run.getProcessedCount())
                .meta("retained", run.getRetainedCount())
                .meta("failed", run.getFailedCount()));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.RETENTION_RUN_COMPLETED)
                .reference("retention_run", runId)
                .durationMs(java.time.Duration.between(run.getStartedAt(), run.getFinishedAt()).toMillis())
                .attr("category", run.getCategory())
                .attr("trigger", run.getTrigger())
                .attr("dryRun", run.isDryRun())
                .attr("candidates", run.getCandidateCount())
                .attr("eligible", run.getEligibleCount())
                .attr("processed", run.getProcessedCount())
                .attr("retained", run.getRetainedCount())
                .attr("failed", run.getFailedCount()));
        return run;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<RetentionRunView> listRuns(PageQuery page) {
        accessScope.requireAllCreditors(RESOURCE);
        return PageResponse.of(runs.findAllByOrderByStartedAtDesc(page.toPageable()), RetentionService::view);
    }

    @Override
    @Transactional(readOnly = true)
    public RetentionRunView getRun(UUID runId) {
        accessScope.requireAllCreditors(RESOURCE);
        return view(runs.findById(runId).orElseThrow(() -> ResourceNotFoundException.of("retention_run", runId)));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<RetentionRunItemView> runItems(UUID runId, PageQuery page) {
        accessScope.requireAllCreditors(RESOURCE);
        runs.findById(runId).orElseThrow(() -> ResourceNotFoundException.of("retention_run", runId));
        return PageResponse.of(items.findByRunIdOrderByProcessedAtAsc(runId, page.toPageable()),
                i -> new RetentionRunItemView(i.getDebtorId(), i.getOutcome().name(), i.getErrorCode(), i.getDetail(),
                        i.getProcessedAt()));
    }

    static RetentionPolicyView view(RetentionPolicy p) {
        return new RetentionPolicyView(p.getCategory().name(), p.getRetentionMonths(), p.getAction().name(), p.isEnabled(),
                p.getCategory().isExecutableHere(), p.getUpdatedAt());
    }

    static RetentionRunView view(RetentionRun r) {
        return new RetentionRunView(r.getId(), r.getCategory().name(), r.getTrigger().name(), r.isDryRun(),
                r.getRetentionMonths(), r.getCutoff(), r.getStartedAt(), r.getFinishedAt(), r.getStatus().name(),
                r.getCandidateCount(), r.getEligibleCount(), r.getProcessedCount(), r.getRetainedCount(),
                r.getFailedCount(), r.getFailureReason());
    }
}
