package br.com.gestorcobranca.campaign.application.service;

import br.com.gestorcobranca.campaign.domain.model.ActionType;
import br.com.gestorcobranca.campaign.domain.model.AudienceSnapshot;
import br.com.gestorcobranca.campaign.domain.model.Cadence;
import br.com.gestorcobranca.campaign.domain.model.CadenceDefinition;
import br.com.gestorcobranca.campaign.domain.model.CadenceStep;
import br.com.gestorcobranca.campaign.domain.model.Campaign;
import br.com.gestorcobranca.campaign.domain.model.EndReason;
import br.com.gestorcobranca.campaign.domain.model.Enrollment;
import br.com.gestorcobranca.campaign.domain.model.EnrollmentStatus;
import br.com.gestorcobranca.campaign.domain.model.PauseReason;
import br.com.gestorcobranca.campaign.domain.model.ReplyTransition;
import br.com.gestorcobranca.campaign.domain.model.SegmentCriteria;
import br.com.gestorcobranca.campaign.domain.model.SkipReason;
import br.com.gestorcobranca.campaign.domain.model.StepCondition;
import br.com.gestorcobranca.campaign.domain.model.StepExecution;
import br.com.gestorcobranca.campaign.domain.model.StepOutcome;
import br.com.gestorcobranca.campaign.domain.repository.AttributionRepository;
import br.com.gestorcobranca.campaign.domain.repository.CadenceRepository;
import br.com.gestorcobranca.campaign.domain.repository.CampaignRepository;
import br.com.gestorcobranca.campaign.domain.repository.EnrollmentRepository;
import br.com.gestorcobranca.campaign.domain.repository.ExperimentRepository;
import br.com.gestorcobranca.campaign.domain.repository.SegmentRepository;
import br.com.gestorcobranca.campaign.domain.repository.StepExecutionRepository;
import br.com.gestorcobranca.campaign.domain.service.PauseRules;
import br.com.gestorcobranca.campaign.domain.service.SegmentMatcher;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.DomainException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.events.campaign.CadenceExhausted;
import br.com.gestorcobranca.shared.jobs.JobContext;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Motor de uma etapa (job {@code campaign.step}). A etapa roda numa transação própria, com a
 * inscrição bloqueada: o registro da execução (chave idempotente) e o efeito (mensagem enfileirada no
 * communication, tarefa no collection, mudança de dono) são confirmados juntos. Erro de negócio de
 * outro módulo (template inexistente, canal sem conta…) desfaz essa transação e é registrado em outra
 * como falha da inscrição ({@code FAILED}, reprocessável com a mesma chave); falha técnica é repetida
 * pelo job e, na última tentativa, registrada do mesmo modo.
 * <p>
 * Ordem: estado/obsolescência → campanha ativa → posição atual do caso (collection) → caso encerrado
 * (fim) → situação que pausa a régua → ação de resposta pendente → saída do segmento → elegibilidade
 * (pula) → condição (pula) → janela da etapa (adia) → porteiro (adia negação temporária até o
 * adiamento máximo; senão pula) → ação → próxima etapa ou fim (régua esgotada publica
 * {@link CadenceExhausted}).
 */
@Component
class StepRunner {

    private static final Logger LOG = LoggerFactory.getLogger(StepRunner.class);
    private static final ZoneId ZONE = TimeConfig.BUSINESS_ZONE;
    /** Erros do communication que significam "sem contato utilizável": a etapa é pulada, não falha. */
    private static final Set<String> NO_CONTACT_CODES = Set.of("message.no_contact", "message.contact_unusable");

    private final EnrollmentRepository enrollments;
    private final CampaignRepository campaigns;
    private final CadenceRepository cadences;
    private final SegmentRepository segments;
    private final StepExecutionRepository executions;
    private final AttributionRepository attributions;
    private final ExperimentRepository experiments;
    private final CampaignCodec codec;
    private final AudienceProjection projection;
    private final EnrollmentLifecycle lifecycle;
    private final ActionExecutor actions;
    private final CampaignEvents events;
    private final CampaignMetrics metrics;
    private final ApplicationEventPublisher publisher;
    private final CurrentActor currentActor;
    private final TransactionTemplate tx;
    private final java.time.Clock clock;

    StepRunner(EnrollmentRepository enrollments, CampaignRepository campaigns, CadenceRepository cadences,
               SegmentRepository segments, StepExecutionRepository executions, AttributionRepository attributions,
               ExperimentRepository experiments, CampaignCodec codec, AudienceProjection projection,
               EnrollmentLifecycle lifecycle, ActionExecutor actions, CampaignEvents events, CampaignMetrics metrics,
               ApplicationEventPublisher publisher, CurrentActor currentActor,
               PlatformTransactionManager transactionManager, java.time.Clock clock) {
        this.enrollments = enrollments;
        this.campaigns = campaigns;
        this.cadences = cadences;
        this.segments = segments;
        this.executions = executions;
        this.attributions = attributions;
        this.experiments = experiments;
        this.codec = codec;
        this.projection = projection;
        this.lifecycle = lifecycle;
        this.actions = actions;
        this.events = events;
        this.metrics = metrics;
        this.publisher = publisher;
        this.currentActor = currentActor;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    void run(JobContext context, StepJob.Payload payload, int maxAttempts) {
        Instant started = Instant.now(clock);
        try {
            tx.executeWithoutResult(status -> execute(payload, started));
        } catch (DomainException e) {
            tx.executeWithoutResult(status -> afterError(payload, e.code(), e.getMessage()));
        } catch (RuntimeException e) {
            if (context.attempt() < maxAttempts) {
                throw e;
            }
            LOG.atError().addKeyValue("enrollmentId", payload.enrollmentId()).addKeyValue("stepCode", payload.stepCode())
                    .setCause(e).log("Etapa de campanha falhou em todas as tentativas; inscrição marcada com falha");
            metrics.jobFailure(StepJob.TYPE);
            tx.executeWithoutResult(status -> afterError(payload, "UNEXPECTED", e.getClass().getSimpleName()));
        }
    }

    /** Contexto carregado de uma execução. */
    private record Run(Enrollment enrollment, Campaign campaign, Cadence cadence, CadenceDefinition definition,
                       StepJob.Payload payload, Instant now) {
    }

    private Optional<Run> load(StepJob.Payload p, Instant now) {
        Enrollment e = enrollments.findByIdForUpdate(p.enrollmentId()).orElse(null);
        if (e == null || e.getStatus() != EnrollmentStatus.ACTIVE || !matches(e, p)) {
            return Optional.empty();
        }
        if (e.getNextRunAt() != null && e.getNextRunAt().isAfter(now.plusSeconds(1))) {
            return Optional.empty(); // reagendada: outro job cuida
        }
        Campaign c = campaigns.findById(e.getCampaignId()).orElseThrow();
        if (!c.isActive()) {
            return Optional.empty(); // pausada/encerrada: a reativação reagenda
        }
        Cadence cadence = cadences.findById(e.getCadenceId()).orElseThrow();
        return Optional.of(new Run(e, c, cadence, codec.cadence(cadence.getDefinition()), p, now));
    }

    private static boolean matches(Enrollment e, StepJob.Payload p) {
        if (StepExecution.REPLY_STEP.equals(p.stepCode())) {
            return e.getPendingReplyAction() != null && p.iteration() == e.nextIteration();
        }
        return e.getPendingReplyAction() == null && p.stepCode().equals(e.getNextStepCode())
                && p.iteration() == e.getNextStepIteration();
    }

    private void execute(StepJob.Payload p, Instant started) {
        Instant now = Instant.now(clock);
        Optional<Run> loaded = load(p, now);
        if (loaded.isEmpty()) {
            return;
        }
        Run run = loaded.get();
        Enrollment e = run.enrollment();
        Campaign c = run.campaign();
        AudienceSnapshot snapshot = projection.refresh(e.getCaseId(), "step");
        if (!snapshot.active()) {
            lifecycle.end(e, c, EndReason.CASE_CLOSED);
            return;
        }
        Optional<PauseReason> blocking = PauseRules.blocking(snapshot, c.getPurpose());
        if (blocking.isPresent()) {
            lifecycle.pause(e, c, blocking.get(), null, "step");
            return;
        }
        if (e.getPendingReplyAction() != null) {
            replyAction(run, snapshot, started);
            return;
        }
        CadenceStep step = variantStep(e, run.definition().step(p.stepCode())
                .orElseThrow(() -> new IllegalStateException("Etapa inexistente na régua: " + p.stepCode())));
        StepExecution.Key key = new StepExecution.Key(e.getId(), c.getId(), run.cadence().getId(), step.code(),
                p.iteration(), step.action().type(), step.action().channel(), e.getVariantCode());
        Optional<StepExecution> done = executions.findCompletedByKey(key.idempotencyKey());
        if (done.isPresent()) {
            // já registrada (reexecução após falha entre o registro e o avanço não ocorre: mesma transação)
            advance(run, step, done.get().getOutcome() == StepOutcome.EXECUTED);
            return;
        }
        if (c.isExitWhenOutOfSegment()) {
            SegmentCriteria criteria = segments.findById(c.getSegmentId()).map(s -> codec.criteria(s.getCriteria()))
                    .orElseThrow();
            List<String> unmet = SegmentMatcher.unmet(criteria, c.getCreditorId(), c.getPortfolioId(), snapshot, now, ZONE);
            if (!unmet.isEmpty()) {
                LOG.atInfo().addKeyValue("enrollmentId", e.getId()).addKeyValue("unmet", String.join(",", unmet))
                        .log("Caso saiu do segmento da campanha; inscrição encerrada");
                lifecycle.end(e, c, EndReason.LEFT_SEGMENT);
                return;
            }
        }
        if (!snapshot.eligible()) {
            skip(run, step, key, SkipReason.CASE_INELIGIBLE, String.join(",", snapshot.eligibilityReasons()), null, false);
            return;
        }
        List<String> unmetCondition = conditionUnmet(step.condition(), e, snapshot, now);
        if (!unmetCondition.isEmpty()) {
            skip(run, step, key, SkipReason.CONDITION_NOT_MET, String.join(",", unmetCondition), null, false);
            return;
        }
        if (!step.window().contains(now, ZONE)) {
            defer(run, step, step.window().nextOpening(now, ZONE), "step_window");
            return;
        }
        ActionExecutor.Result result = actions.execute(e, step, snapshot, key.idempotencyKey(), now);
        switch (result) {
            case ActionExecutor.Executed x -> executed(run, step, key, x, started);
            case ActionExecutor.Skipped s -> skip(run, step, key, s.reason(), s.detail(), s.messageId(), s.terminalApplied());
            case ActionExecutor.Deferred d -> {
                Instant limit = (e.getStepDueAt() == null ? now : e.getStepDueAt())
                        .plus(Duration.ofHours(c.getMaxDeferralHours()));
                if (d.until().isAfter(limit)) {
                    skip(run, step, key, SkipReason.CONTACT_DENIED, d.detail(), null, false);
                } else {
                    defer(run, step, step.window().nextOpening(d.until(), ZONE), d.detail());
                }
            }
        }
    }

    private CadenceStep variantStep(Enrollment e, CadenceStep step) {
        if (e.getExperimentId() == null || e.getVariantCode() == null) {
            return step;
        }
        return experiments.findById(e.getExperimentId())
                .flatMap(x -> codec.variants(x.getVariants()).stream()
                        .filter(v -> v.code().equals(e.getVariantCode())).findFirst())
                .flatMap(v -> v.overrideFor(step.code()))
                .map(o -> o.applyTo(step))
                .orElse(step);
    }

    private List<String> conditionUnmet(StepCondition condition, Enrollment e, AudienceSnapshot snapshot, Instant now) {
        List<String> unmet = new ArrayList<>();
        if (condition.criteria() != null) {
            unmet.addAll(SegmentMatcher.unmet(condition.criteria(), null, null, snapshot, now, ZONE));
        }
        if (condition.requireNoReply() && e.getRepliedAt() != null) {
            unmet.add("requireNoReply");
        }
        if (condition.requireNoEffectiveContact() && e.getEffectiveContactAt() != null) {
            unmet.add("requireNoEffectiveContact");
        }
        if (condition.requirePreviousMessageUnread() && e.getLastMessageId() != null
                && executions.findByMessageId(e.getLastMessageId())
                .map(s -> "READ".equals(s.getMessageStatus())).orElse(false)) {
            unmet.add("requirePreviousMessageUnread");
        }
        return unmet;
    }

    private void executed(Run run, CadenceStep step, StepExecution.Key key, ActionExecutor.Executed x, Instant started) {
        Enrollment e = run.enrollment();
        StepExecution saved = executions.saveAndFlush(StepExecution.executed(key, x.messageId(), x.messageStatus(),
                x.taskId(), run.now()));
        e.recordStep(step.code(), StepOutcome.EXECUTED, x.messageId(), run.now());
        events.stepExecuted(e, run.campaign().getCode(), run.cadence().getVersionNumber(), saved, started, run.now());
        metrics.step(key.actionType().name(), key.channel(), "executed");
        metrics.stepDuration(key.actionType().name(), Duration.between(started, Instant.now(clock)));
        advance(run, step, true);
    }

    private void skip(Run run, CadenceStep step, StepExecution.Key key, String reason, String detail,
                      java.util.UUID messageId, boolean terminalApplied) {
        Enrollment e = run.enrollment();
        StepExecution saved = executions.saveAndFlush(StepExecution.skipped(key, reason, detail, messageId, run.now()));
        e.recordStep(step.code(), StepOutcome.SKIPPED, null, run.now());
        events.stepSkipped(e, run.campaign().getCode(), run.cadence().getVersionNumber(), saved);
        metrics.step(key.actionType().name(), key.channel(), "skipped");
        metrics.stepSkipped(reason);
        advance(run, step, terminalApplied);
    }

    private void defer(Run run, CadenceStep step, Instant until, String detail) {
        Enrollment e = run.enrollment();
        e.defer(until);
        enrollments.save(e);
        lifecycle.schedule(e);
        metrics.step(step.action().type().name(), step.action().channel(), "deferred");
        LOG.atDebug().addKeyValue("enrollmentId", e.getId()).addKeyValue("stepCode", step.code())
                .addKeyValue("until", until).addKeyValue("detail", detail).log("Etapa adiada");
    }

    /** Próxima etapa, fim por ação terminal ou fim da régua. */
    private void advance(Run run, CadenceStep step, boolean terminalApplied) {
        Enrollment e = run.enrollment();
        Campaign c = run.campaign();
        if (step.action().terminal() && terminalApplied) {
            lifecycle.end(e, c, step.action().type() == ActionType.HAND_TO_ASSISTANT ? EndReason.HANDED_TO_ASSISTANT
                    : EndReason.MOVED_TO_QUEUE);
            return;
        }
        Optional<CadenceStep> next = run.definition().after(step.code());
        if (next.isEmpty() || e.runsExhausted()) {
            finishCadence(run);
            return;
        }
        e.scheduleNext(next.get().code(), e.nextIteration(), run.now().plus(Duration.ofMinutes(next.get().waitMinutes())));
        enrollments.save(e);
        lifecycle.schedule(e);
    }

    /** Régua percorrida: concluída com resultado, ou esgotada (insumo do jurídico). */
    private void finishCadence(Run run) {
        Enrollment e = run.enrollment();
        Campaign c = run.campaign();
        boolean outcome = e.getRepliedAt() != null || e.getEffectiveContactAt() != null || attributions.existsAny(e.getId());
        lifecycle.end(e, c, outcome ? EndReason.CADENCE_COMPLETED : EndReason.CADENCE_EXHAUSTED);
        if (outcome) {
            return;
        }
        int messagesSent = (int) executions.findByEnrollmentIdOrderByExecutedAt(e.getId()).stream()
                .filter(s -> s.getOutcome() == StepOutcome.EXECUTED && s.getMessageId() != null).count();
        publisher.publishEvent(new CadenceExhausted(Ids.newId(), currentActor.tenantId(), run.now(), e.getId(),
                c.getId(), c.getPurpose().name(), run.cadence().getVersionNumber(), e.getCaseId(), e.getDebtorId(),
                e.getCreditorId(), e.getPortfolioId(), e.getStepsExecuted(), e.getStepsSkipped(), messagesSent,
                e.getEnrolledAt()));
        events.cadenceExhausted(e, c, run.cadence().getVersionNumber(), messagesSent);
        metrics.cadenceExhausted(c.getPurpose().name());
    }

    /** Transição por resposta que exige ação no caso (entregar ao assistente, mover para a fila). */
    private void replyAction(Run run, AudienceSnapshot snapshot, Instant started) {
        Enrollment e = run.enrollment();
        ReplyTransition transition = e.getPendingReplyAction();
        ActionType type = transition == ReplyTransition.HAND_TO_ASSISTANT ? ActionType.HAND_TO_ASSISTANT
                : ActionType.MOVE_TO_QUEUE;
        StepExecution.Key key = new StepExecution.Key(e.getId(), run.campaign().getId(), run.cadence().getId(),
                StepExecution.REPLY_STEP, e.nextIteration(), type, null, e.getVariantCode());
        ActionExecutor.Result result = actions.caseAction(type, run.definition().replyTeamId(), e, snapshot,
                "Resposta do devedor à régua da campanha");
        StepExecution saved = result instanceof ActionExecutor.Skipped s
                ? executions.saveAndFlush(StepExecution.skipped(key, s.reason(), s.detail(), null, run.now()))
                : executions.saveAndFlush(StepExecution.executed(key, null, null, null, run.now()));
        e.clearReplyAction();
        e.recordStep(StepExecution.REPLY_STEP, saved.getOutcome(), null, run.now());
        if (saved.getOutcome() == StepOutcome.EXECUTED) {
            events.stepExecuted(e, run.campaign().getCode(), run.cadence().getVersionNumber(), saved, started, run.now());
        } else {
            events.stepSkipped(e, run.campaign().getCode(), run.cadence().getVersionNumber(), saved);
        }
        metrics.step(type.name(), null, saved.getOutcome() == StepOutcome.EXECUTED ? "executed" : "skipped");
        lifecycle.end(e, run.campaign(), type == ActionType.HAND_TO_ASSISTANT ? EndReason.HANDED_TO_ASSISTANT
                : EndReason.MOVED_TO_QUEUE);
    }

    /** Erro na etapa (transação já desfeita): pula se for falta de contato; senão marca a inscrição com falha. */
    private void afterError(StepJob.Payload p, String code, String detail) {
        Instant now = Instant.now(clock);
        Optional<Run> loaded = load(p, now);
        if (loaded.isEmpty()) {
            return;
        }
        Run run = loaded.get();
        Enrollment e = run.enrollment();
        boolean reply = StepExecution.REPLY_STEP.equals(p.stepCode());
        CadenceStep step = reply ? null : variantStep(e, run.definition().step(p.stepCode()).orElseThrow());
        ActionType type = reply ? (e.getPendingReplyAction() == ReplyTransition.HAND_TO_ASSISTANT
                ? ActionType.HAND_TO_ASSISTANT : ActionType.MOVE_TO_QUEUE) : step.action().type();
        String channel = reply ? null : step.action().channel();
        StepExecution.Key key = new StepExecution.Key(e.getId(), run.campaign().getId(), run.cadence().getId(),
                p.stepCode(), p.iteration(), type, channel, e.getVariantCode());
        if (!reply && NO_CONTACT_CODES.contains(code)) {
            skip(run, step, key, SkipReason.NO_CONTACT, code, null, false);
            return;
        }
        executions.saveAndFlush(StepExecution.failed(key, code, detail, now));
        e.fail(code, now);
        enrollments.save(e);
        metrics.step(type.name(), channel, "failed");
        metrics.stepFailed(code);
        LOG.atWarn().addKeyValue("enrollmentId", e.getId()).addKeyValue("campaignId", e.getCampaignId())
                .addKeyValue("stepCode", p.stepCode()).addKeyValue("errorCode", code)
                .log("Etapa de campanha com erro; inscrição aguarda reprocessamento");
    }
}
