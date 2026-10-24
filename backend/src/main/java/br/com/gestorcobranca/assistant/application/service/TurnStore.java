package br.com.gestorcobranca.assistant.application.service;

import br.com.gestorcobranca.assistant.domain.model.AssistantSession;
import br.com.gestorcobranca.assistant.domain.model.AssistantTurn;
import br.com.gestorcobranca.assistant.domain.model.HandoffReason;
import br.com.gestorcobranca.assistant.domain.model.PixRequest;
import br.com.gestorcobranca.assistant.domain.model.SampleReason;
import br.com.gestorcobranca.assistant.domain.model.SessionMemory;
import br.com.gestorcobranca.assistant.domain.model.ToolCallRecord;
import br.com.gestorcobranca.assistant.domain.model.TurnOutcome;
import br.com.gestorcobranca.assistant.domain.model.TurnReview;
import br.com.gestorcobranca.assistant.domain.repository.AssistantSessionRepository;
import br.com.gestorcobranca.assistant.domain.repository.AssistantTurnRepository;
import br.com.gestorcobranca.assistant.domain.repository.PixRequestRepository;
import br.com.gestorcobranca.assistant.domain.repository.ToolCallRepository;
import br.com.gestorcobranca.assistant.domain.repository.TurnReviewRepository;
import br.com.gestorcobranca.assistant.domain.service.ReviewSampler;
import br.com.gestorcobranca.communication.application.port.in.ConversationUseCases.ConversationView;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Gravações do turno em transações próprias (o turno chama outros módulos entre elas): sessão, marca de
 * início e o registro final — turno, chamadas de ferramenta, pedidos de Pix (com o job de entrega),
 * sessão atualizada, amostra de revisão e eventos de negócio — numa única transação.
 */
@Component
class TurnStore {

    private final AssistantSessionRepository sessions;
    private final AssistantTurnRepository turns;
    private final ToolCallRepository toolCalls;
    private final TurnReviewRepository reviews;
    private final AssistantCodec codec;
    private final BusinessEventLogger businessEvents;
    private final AssistantMetrics metrics;
    private final PixRequestRepository pixRequests;
    private final AssistantPixRecorder pixRecorder;
    private final JobQueue jobQueue;
    private final Clock clock;

    TurnStore(AssistantSessionRepository sessions, AssistantTurnRepository turns, ToolCallRepository toolCalls,
              TurnReviewRepository reviews, AssistantCodec codec, BusinessEventLogger businessEvents,
              AssistantMetrics metrics, PixRequestRepository pixRequests, AssistantPixRecorder pixRecorder,
              JobQueue jobQueue, Clock clock) {
        this.pixRequests = pixRequests;
        this.pixRecorder = pixRecorder;
        this.jobQueue = jobQueue;
        this.sessions = sessions;
        this.turns = turns;
        this.toolCalls = toolCalls;
        this.reviews = reviews;
        this.codec = codec;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** Sessão da conversa (criada no primeiro turno), com os vínculos atuais de caso e carteira. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    AssistantSession session(ConversationView c, boolean identityRequired) {
        AssistantSession session = sessions.findByConversationId(c.id())
                .orElseGet(() -> new AssistantSession(c.id(), c.creditorId(), c.debtorId(), c.portfolioId(), c.caseId(),
                        identityRequired, codec.memory(SessionMemory.EMPTY)));
        session.relink(c.portfolioId(), c.caseId());
        return sessions.saveAndFlush(session);
    }

    /** Nova etapa: a conversa voltou ao assistente depois de transferida. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    AssistantSession reopen(AssistantSession session, boolean identityRequired) {
        session.reopen(Instant.now(clock), identityRequired, codec.memory(SessionMemory.EMPTY));
        return sessions.saveAndFlush(session);
    }

    /** Marca o início do turno: as mensagens até {@code inboundUpTo} são deste turno. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    AssistantSession begin(AssistantSession session, UUID token, Instant inboundUpTo, UUID policyId) {
        session.begin(token, inboundUpTo, Instant.now(clock), policyId);
        return sessions.saveAndFlush(session);
    }

    /** Registro final do turno (append-only) e efeitos na sessão. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    AssistantTurn record(TurnContext ctx) {
        Instant finished = Instant.now(clock);
        AssistantSession session = ctx.session;
        long total = Math.max(0, Duration.between(ctx.startedAt, finished).toMillis());
        TurnOutcome outcome = ctx.outcome == null ? TurnOutcome.SKIPPED : ctx.outcome;
        AssistantTurn turn = turns.save(new AssistantTurn(new AssistantTurn.Record(session.getId(), ctx.conversation.id(),
                ctx.conversation.creditorId(), ctx.conversation.portfolioId(), ctx.conversation.debtorId(),
                ctx.conversation.caseId(), ctx.hasPolicy() ? ctx.policy.policy().getId() : null,
                ctx.hasPolicy() ? ctx.policy.policy().getPolicyVersion() : null,
                ctx.prompt == null ? null : ctx.prompt.code(), ctx.prompt == null ? null : ctx.prompt.version(),
                ctx.prompt == null ? null : ctx.prompt.sha256(), ctx.mode, session.getEpisode(), session.getTurns() + 1,
                ctx.triggerMessageId, codec.toJson(ctx.inputMessageIds), blankToNull(ctx.inputText), ctx.lastContext,
                ctx.lastContextHash, ctx.outputText, outcome, ctx.handoffReason, ctx.triggerSource,
                codec.toJson(ctx.guardrails), ctx.intent, ctx.confidence, ctx.provider, ctx.model, ctx.modelCalls,
                ctx.toolCalls.size(), ctx.inputTokens, ctx.outputTokens, ctx.cost, ctx.costCurrency, ctx.modelLatencyMs,
                total, ctx.outboundMessageId, ctx.draftId, ctx.proposalId, ctx.agreementId, ctx.errorCode, ctx.startedAt,
                finished)));
        for (TurnContext.ToolCallLog call : ctx.toolCalls) {
            toolCalls.save(new ToolCallRecord(turn.getId(), call.sequence(), call.tool(), call.arguments(), call.result(),
                    call.outcome(), call.errorCode(), call.referenceType(), call.referenceId(), call.durationMs()));
        }
        for (PixRequest request : ctx.pixRequests) {
            request.attachTo(turn.getId());
            PixRequest saved = pixRequests.save(request);
            pixRecorder.evaluated(saved, ctx.conversation.channel());
            if (saved.getDeliveryStatus() != null && saved.getDeliveryStatus().open()) {
                AssistantPixDelivery.enqueue(jobQueue, saved.getId());
            }
        }
        session.remember(codec.memory(ctx.memory));
        session.finish(ctx.token, ctx.inputTokens, ctx.outputTokens, ctx.cost, ctx.costCurrency);
        sessions.save(session);
        sample(ctx, turn);
        log(ctx, turn, outcome, total);
        metrics.turn(ctx.mode.name(), outcome.name(), Duration.ofMillis(total));
        ctx.guardrails.forEach(metrics::guardrail);
        if (ctx.handoffReason != null) {
            metrics.handoff(ctx.handoffReason.name(), ctx.triggerSource == null ? "NA" : ctx.triggerSource.name());
        }
        if (outcome == TurnOutcome.DRAFTED) {
            metrics.draft(ctx.mode.name());
        }
        return turn;
    }

    private void sample(TurnContext ctx, AssistantTurn turn) {
        SampleReason reason = null;
        if (!ctx.guardrails.isEmpty()) {
            reason = SampleReason.GUARDRAIL;
        } else if (turn.getOutcome() == TurnOutcome.FAILED) {
            reason = SampleReason.FAILURE;
        } else if (ctx.hasPolicy() && ReviewSampler.sampled(turn.getId(), ctx.policy.settings().reviewSampleRate())) {
            reason = SampleReason.RANDOM;
        }
        if (reason != null) {
            reviews.save(new TurnReview(turn, reason));
        }
    }

    private void log(TurnContext ctx, AssistantTurn turn, TurnOutcome outcome, long totalMs) {
        String type = switch (outcome) {
            case REPLIED -> BusinessEventTypes.ASSISTANT_REPLIED;
            case DRAFTED -> BusinessEventTypes.ASSISTANT_DRAFT_PROPOSED;
            case HANDED_OFF -> BusinessEventTypes.ASSISTANT_HANDOFF_TRIGGERED;
            case APPROVAL_REQUESTED -> BusinessEventTypes.ASSISTANT_APPROVAL_REQUESTED;
            case SEND_BLOCKED -> BusinessEventTypes.ASSISTANT_REPLY_BLOCKED;
            case GUARDRAIL_BLOCKED -> BusinessEventTypes.ASSISTANT_GUARDRAIL_BLOCKED;
            case FAILED -> BusinessEventTypes.ASSISTANT_TURN_FAILED;
            case SKIPPED -> null;
        };
        if (!ctx.guardrails.isEmpty() && outcome != TurnOutcome.GUARDRAIL_BLOCKED) {
            businessEvents.log(base(BusinessEventTypes.ASSISTANT_GUARDRAIL_BLOCKED, ctx, turn)
                    .attr("codes", String.join(",", ctx.guardrails)).attr("action", "FALLBACK_HANDOFF"));
        }
        if (type == null) {
            return;
        }
        BusinessEvent event = base(type, ctx, turn).durationMs(totalMs)
                .attr("intent", ctx.intent)
                .attr("toolCalls", ctx.toolCalls.size())
                .attr("modelCalls", ctx.modelCalls)
                .attr("identity", ctx.session.getIdentityStatus())
                .attr("inputTokens", ctx.inputTokens)
                .attr("outputTokens", ctx.outputTokens)
                .attr("cost", ctx.cost == null ? BigDecimal.ZERO : ctx.cost)
                .attr("costCurrency", ctx.costCurrency);
        if (ctx.handoffReason != null) {
            event.attr("reason", ctx.handoffReason).attr("source", ctx.triggerSource)
                    .attr("requiredProfile", requiredProfile(ctx, ctx.handoffReason));
        }
        if (outcome == TurnOutcome.GUARDRAIL_BLOCKED) {
            event.attr("codes", String.join(",", ctx.guardrails)).attr("action", "DRAFT_SUPPRESSED");
        }
        if (outcome == TurnOutcome.SEND_BLOCKED) {
            event.attr("reasons", ctx.blockReasons);
        }
        if (outcome == TurnOutcome.FAILED) {
            event.attr("errorCode", ctx.errorCode);
        }
        if (ctx.proposalId != null) {
            event.attr("proposalId", ctx.proposalId.toString());
        }
        if (ctx.agreementId != null) {
            event.attr("agreementId", ctx.agreementId.toString());
        }
        if (ctx.memory.proposal() != null) {
            event.amount(ctx.memory.proposal().total());
        }
        businessEvents.log(event);
    }

    private BusinessEvent base(String type, TurnContext ctx, AssistantTurn turn) {
        return BusinessEvent.of(type).creditor(ctx.conversation.creditorId()).portfolio(ctx.conversation.portfolioId())
                .debtor(ctx.conversation.debtorId()).collectionCase(ctx.conversation.caseId())
                .reference("assistant_turn", turn.getId()).channel(ctx.conversation.channel())
                .attr("conversationId", ctx.conversation.id().toString())
                .attr("mode", ctx.mode)
                .attr("policyId", ctx.hasPolicy() ? ctx.policy.policy().getId().toString() : null)
                .attr("policyVersion", ctx.hasPolicy() ? ctx.policy.policy().getPolicyVersion() : null)
                .attr("promptVersion", ctx.prompt == null ? null : ctx.prompt.code() + "@" + ctx.prompt.version())
                .attr("episode", turn.getEpisode())
                .attr("turn", turn.getTurnNumber());
    }

    private static String requiredProfile(TurnContext ctx, HandoffReason reason) {
        return ctx.hasPolicy() ? ctx.policy.settings().requiredProfileFor(reason) : reason.defaultProfile();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
