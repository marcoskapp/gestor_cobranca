package br.com.gestorcobranca.assistant.application.service;

import br.com.gestorcobranca.assistant.application.AssistantProperties;
import br.com.gestorcobranca.assistant.application.port.out.ConversationLocks;
import br.com.gestorcobranca.assistant.application.port.out.LanguageModelPort;
import br.com.gestorcobranca.assistant.application.port.out.LanguageModelPort.ModelException;
import br.com.gestorcobranca.assistant.application.port.out.LanguageModelPort.ModelMessage;
import br.com.gestorcobranca.assistant.application.port.out.LanguageModelPort.ModelRequest;
import br.com.gestorcobranca.assistant.application.port.out.LanguageModelPort.ModelResponse;
import br.com.gestorcobranca.assistant.application.port.out.LanguageModelPort.ToolCall;
import br.com.gestorcobranca.assistant.application.service.AssistantToolbox.Terminal;
import br.com.gestorcobranca.assistant.application.service.AssistantToolbox.ToolOutcome;
import br.com.gestorcobranca.assistant.domain.model.AssistantMode;
import br.com.gestorcobranca.assistant.domain.model.AssistantSession;
import br.com.gestorcobranca.assistant.domain.model.HandoffReason;
import br.com.gestorcobranca.assistant.domain.model.IdentityStatus;
import br.com.gestorcobranca.assistant.domain.model.PolicySettings;
import br.com.gestorcobranca.assistant.domain.model.SessionMemory;
import br.com.gestorcobranca.assistant.domain.model.SessionStatus;
import br.com.gestorcobranca.assistant.domain.model.ToolCallOutcome;
import br.com.gestorcobranca.assistant.domain.model.TriggerSource;
import br.com.gestorcobranca.assistant.domain.model.TurnOutcome;
import br.com.gestorcobranca.assistant.domain.service.ContextBuilder;
import br.com.gestorcobranca.assistant.domain.service.HandoffDetector;
import br.com.gestorcobranca.assistant.domain.service.HandoffDetector.Detection;
import br.com.gestorcobranca.assistant.domain.service.IdentityChallenge;
import br.com.gestorcobranca.assistant.domain.service.ModelContext;
import br.com.gestorcobranca.assistant.domain.service.PersonalDataRedactor;
import br.com.gestorcobranca.assistant.domain.service.ResponseGuard;
import br.com.gestorcobranca.assistant.domain.service.ServiceHours;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases;
import br.com.gestorcobranca.communication.application.port.in.ConversationUseCases;
import br.com.gestorcobranca.communication.application.port.in.ConversationUseCases.ConversationView;
import br.com.gestorcobranca.communication.application.port.in.MessageUseCases;
import br.com.gestorcobranca.communication.application.port.in.MessageUseCases.MessageView;
import br.com.gestorcobranca.debt.application.port.in.DebtorIdentityUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.DomainException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.jobs.JobQueue;
import br.com.gestorcobranca.shared.paging.PageQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Orquestração de um turno do assistente ("a IA conversa, o motor decide"), executada pelo job
 * {@code assistant.turn} sob lock consultivo da conversa:
 * <ol>
 *   <li>decide pela política vigente e pelo dono da conversa: responder (dono {@code ASSISTANT}, modos
 *       autônomos), rascunhar (dono humano, modos sombra/copiloto), transferir (política desligada ou
 *       incompatível) ou adiar (fora do horário);</li>
 *   <li>limites (turnos, custo), gatilhos determinísticos de transferência e opt-out, confirmação de
 *       identidade — tudo antes do modelo;</li>
 *   <li>conversa com o modelo pela {@link LanguageModelPort} com contexto minimizado e as ferramentas do
 *       {@link AssistantToolbox} (validação no servidor);</li>
 *   <li>guardrails de saída e envio pelo communication (porteiro de contato), ou rascunho;</li>
 *   <li>registro append-only do turno ({@link TurnStore}).</li>
 * </ol>
 * Qualquer falha técnica do modelo ou inesperada transfere a conversa para humanos: o devedor nunca fica
 * sem atendimento por causa do assistente.
 */
@Component
class TurnOrchestrator {

    private static final Logger LOG = LoggerFactory.getLogger(TurnOrchestrator.class);

    private enum Action { NONE, DEFER, HANDOFF, AUTONOMOUS, DRAFT }

    private record Decision(Action action, HandoffReason reason, Instant deferUntil) {
    }

    /** Resultado da conversa com o modelo: resposta final ou fim pedido por ferramenta/limite. */
    private record Reply(String text, Terminal terminal) {
    }

    private final ConversationUseCases conversations;
    private final MessageUseCases messages;
    private final CaseUseCases cases;
    private final DebtorUseCases debtors;
    private final DebtorIdentityUseCases identity;
    private final PolicyLookup lookup;
    private final PromptCatalog prompts;
    private final AssistantActors actors;
    private final AssistantChannel channel;
    private final AssistantToolbox toolbox;
    private final ObjectProvider<LanguageModelPort> models;
    private final TurnStore store;
    private final ConversationLocks locks;
    private final JobQueue jobQueue;
    private final AssistantCodec codec;
    private final AssistantMetrics metrics;
    private final AssistantProperties properties;
    private final Clock clock;

    TurnOrchestrator(ConversationUseCases conversations, MessageUseCases messages, CaseUseCases cases,
                     DebtorUseCases debtors, DebtorIdentityUseCases identity, PolicyLookup lookup, PromptCatalog prompts,
                     AssistantActors actors, AssistantChannel channel, AssistantToolbox toolbox,
                     ObjectProvider<LanguageModelPort> models, TurnStore store, ConversationLocks locks,
                     JobQueue jobQueue, AssistantCodec codec, AssistantMetrics metrics, AssistantProperties properties,
                     Clock clock) {
        this.conversations = conversations;
        this.messages = messages;
        this.cases = cases;
        this.debtors = debtors;
        this.identity = identity;
        this.lookup = lookup;
        this.prompts = prompts;
        this.actors = actors;
        this.channel = channel;
        this.toolbox = toolbox;
        this.models = models;
        this.store = store;
        this.locks = locks;
        this.jobQueue = jobQueue;
        this.codec = codec;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Executa o turno da conversa (na transação do job, como sistema). As chamadas aos outros módulos e
     * as gravações do turno usam transações próprias.
     */
    void run(UUID tenantId, UUID conversationId) {
        locks.lockConversation(conversationId);
        ConversationView c = conversations.get(conversationId).conversation();
        if (!"OPEN".equals(c.status()) || c.debtorId() == null || c.creditorId() == null) {
            metrics.scheduled("skipped_conversation");
            return;
        }
        Optional<PolicyLookup.Effective> policy = lookup.effective(c.creditorId(), c.portfolioId());
        Instant now = Instant.now(clock);
        Decision decision = decide(c, policy, now);
        if (decision.action() == Action.NONE) {
            metrics.scheduled("not_applicable");
            return;
        }
        if (decision.action() == Action.DEFER) {
            jobQueue.enqueue(AssistantTurnJob.TYPE, new AssistantTurnJob.Payload(conversationId, null),
                    decision.deferUntil(), "deferred:" + conversationId);
            metrics.scheduled("deferred");
            return;
        }
        boolean identityRequired = policy.map(p -> p.settings().requireIdentityVerification()).orElse(true);
        AssistantSession session = store.session(c, identityRequired);
        if (session.getPendingTurnId() != null) {
            recover(tenantId, c, policy.orElse(null), session);
            return;
        }
        if (decision.action() == Action.AUTONOMOUS && session.getStatus() == SessionStatus.HANDED_OFF) {
            session = store.reopen(session, identityRequired);
        }
        List<MessageView> history = history(c.id());
        List<MessageView> pending = unprocessed(history, session);
        if (pending.isEmpty()) {
            metrics.scheduled("no_new_messages");
            return;
        }
        AssistantMode mode = policy.map(p -> p.settings().mode()).orElse(AssistantMode.OFF);
        TurnContext ctx = new TurnContext(tenantId, c, policy.orElse(null), mode,
                policy.map(p -> actors.forPolicy(tenantId, p.policy(), mode, c.creditorId())).orElse(null), Ids.newId(),
                now);
        Instant upTo = pending.stream().map(TurnOrchestrator::receivedAt).max(Comparator.naturalOrder()).orElse(now);
        ctx.session = store.begin(session, ctx.token, upTo, policy.map(p -> p.policy().getId()).orElse(null));
        ctx.memory = codec.memory(ctx.session.getMemory());
        ctx.inputMessageIds = pending.stream().map(MessageView::id).toList();
        ctx.triggerMessageId = pending.getLast().id();
        ctx.inputText = String.join("\n", pending.stream().map(MessageView::content).toList());
        try {
            switch (decision.action()) {
                case HANDOFF -> handoff(ctx, decision.reason(), TriggerSource.POLICY, null, null, null, false);
                case AUTONOMOUS -> autonomous(ctx, history);
                case DRAFT -> draft(ctx, history);
                default -> throw new IllegalStateException("Ação inesperada: " + decision.action());
            }
        } catch (RuntimeException e) {
            fail(ctx, e, decision.action() != Action.DRAFT);
        }
        store.record(ctx);
    }

    /**
     * Turno interrompido (instância caiu entre o início e o registro): registra a falha e, se a conversa
     * ainda está com o assistente, transfere para humanos.
     */
    void recoverStale(UUID tenantId, UUID conversationId) {
        locks.lockConversation(conversationId);
        ConversationView c = conversations.get(conversationId).conversation();
        if (c.debtorId() == null || c.creditorId() == null) {
            return;
        }
        Optional<PolicyLookup.Effective> policy = lookup.effective(c.creditorId(), c.portfolioId());
        AssistantSession session = store.session(c, policy.map(p -> p.settings().requireIdentityVerification()).orElse(true));
        if (session.getPendingTurnId() != null) {
            recover(tenantId, c, policy.orElse(null), session);
        }
    }

    // ------------------------------------------------------------------ decisão

    private Decision decide(ConversationView c, Optional<PolicyLookup.Effective> policy, Instant now) {
        String owner = c.ownerType();
        if ("ASSISTANT".equals(owner)) {
            if (policy.isEmpty() || !policy.get().settings().mode().autonomous()) {
                return new Decision(Action.HANDOFF, HandoffReason.ASSISTANT_DISABLED, null);
            }
            PolicySettings s = policy.get().settings();
            if (!s.channels().contains(c.channel())) {
                return new Decision(Action.HANDOFF, HandoffReason.CHANNEL_NOT_ENABLED, null);
            }
            ZonedDateTime at = now.atZone(TimeConfig.BUSINESS_ZONE);
            if (!ServiceHours.isOpen(s.serviceHours(), at)) {
                return ServiceHours.nextOpening(s.serviceHours(), at)
                        .map(next -> new Decision(Action.DEFER, null, next.toInstant()))
                        .orElse(new Decision(Action.HANDOFF, HandoffReason.ASSISTANT_DISABLED, null));
            }
            return new Decision(Action.AUTONOMOUS, null, null);
        }
        if (("USER".equals(owner) || "QUEUE".equals(owner)) && policy.isPresent()
                && policy.get().settings().mode().drafts() && policy.get().settings().channels().contains(c.channel())) {
            return new Decision(Action.DRAFT, null, null);
        }
        return new Decision(Action.NONE, null, null);
    }

    // ------------------------------------------------------------------ modo autônomo

    private void autonomous(TurnContext ctx, List<MessageView> history) {
        PolicySettings s = ctx.policy.settings();
        AssistantSession session = ctx.session;
        if (session.getTurns() >= s.maxTurns()) {
            handoff(ctx, HandoffReason.TURN_LIMIT, TriggerSource.LIMIT, session.getTurns() + " turnos", null, null, false);
            return;
        }
        if (session.getCostAmount().compareTo(s.limits().maxCostPerConversation()) >= 0) {
            handoff(ctx, HandoffReason.COST_LIMIT, TriggerSource.LIMIT, "custo acumulado da conversa", null, null, false);
            return;
        }
        List<Detection> detections = HandoffDetector.detect(ctx.inputText, s.extraTriggers());
        if (!detections.isEmpty()) {
            boolean optOut = detections.stream().anyMatch(d -> d.reason() == HandoffReason.OPT_OUT);
            if (optOut) {
                registerOptOut(ctx);
            }
            Detection primary = detections.stream().filter(d -> d.reason() != HandoffReason.OPT_OUT).findFirst()
                    .orElse(detections.getFirst());
            handoff(ctx, primary.reason(), TriggerSource.RULE, "regra: " + primary.rule(), null, null, optOut);
            return;
        }
        if (s.requireIdentityVerification() && !session.identityConfirmed()) {
            if (session.getIdentityStatus() == IdentityStatus.FAILED || !verifyIdentity(ctx)) {
                return;
            }
        }
        loadCaseFacts(ctx);
        Reply reply = converse(ctx, history);
        if (reply.terminal() != null) {
            applyTerminal(ctx, reply.terminal());
            return;
        }
        ResponseGuard.Result guard = ResponseGuard.check(reply.text(), ctx.memory.facts(),
                new ResponseGuard.Config(s.forbiddenTopics(), s.allowCreditBureauMention()));
        ctx.outputText = reply.text();
        if (!guard.approved()) {
            ctx.guardrails.addAll(guard.violations());
            boolean notified = sendQuietly(ctx, s.fallbackMessage());
            handoff(ctx, HandoffReason.GUARDRAIL, TriggerSource.GUARDRAIL, String.join(",", guard.violations()), null,
                    null, notified);
            return;
        }
        Optional<MessageView> sent = channel.send(ctx, reply.text());
        if (sent.isEmpty()) {
            ctx.end(TurnOutcome.SKIPPED, null, null);
            ctx.errorCode = "CONVERSATION_LEFT_ASSISTANT";
            return;
        }
        ctx.outboundMessageId = sent.get().id();
        if ("FAILED".equals(sent.get().status())) {
            ctx.blockReasons = sent.get().failureDetail();
            ctx.errorCode = sent.get().failureCode();
            ctx.end(TurnOutcome.SEND_BLOCKED, null, null);
            return;
        }
        ctx.end(TurnOutcome.REPLIED, null, null);
    }

    /** @return identidade confirmada (ou ainda pendente sem resposta nesta mensagem); falso se o turno terminou */
    private boolean verifyIdentity(TurnContext ctx) {
        PolicySettings s = ctx.policy.settings();
        AssistantSession session = ctx.session;
        if (session.getIdentityStatus() == IdentityStatus.FAILED) {
            handoff(ctx, HandoffReason.IDENTITY_NOT_CONFIRMED, TriggerSource.LIMIT, "identidade não confirmada", null,
                    null, false);
            return false;
        }
        Optional<IdentityChallenge.Answer> answer = IdentityChallenge.parse(ctx.inputText);
        if (answer.isEmpty()) {
            return true;
        }
        DebtorIdentityUseCases.IdentityCheck check = actors.as(ctx.actor, () -> identity.check(ctx.conversation.debtorId(),
                answer.get().documentPrefix(), answer.get().birthDate()));
        metrics.identity(check.outcome());
        if (check.matched()) {
            session.identityVerified(Instant.now(clock));
            return true;
        }
        if ("UNAVAILABLE".equals(check.outcome())) {
            handoff(ctx, HandoffReason.IDENTITY_NOT_CONFIRMED, TriggerSource.LIMIT,
                    "cadastro sem dados para confirmação automática", null, null, false);
            return false;
        }
        session.identityFailed(s.maxIdentityAttempts());
        if (session.getIdentityStatus() == IdentityStatus.FAILED) {
            handoff(ctx, HandoffReason.IDENTITY_NOT_CONFIRMED, TriggerSource.LIMIT,
                    session.getIdentityAttempts() + " tentativas sem confirmação", null, null, false);
            return false;
        }
        return true;
    }

    private void applyTerminal(TurnContext ctx, Terminal t) {
        if (t.outcome() == TurnOutcome.APPROVAL_REQUESTED) {
            sendQuietly(ctx, ctx.policy.settings().approvalMessage());
            channel.handoff(ctx, t.reason(), summary(ctx, t.reason(), t.source(), t.note()), t.referenceType(),
                    t.referenceId());
            ctx.session.handedOff(t.reason(), Instant.now(clock));
            ctx.end(TurnOutcome.APPROVAL_REQUESTED, t.reason(), t.source());
            return;
        }
        if (t.transferRequested()) {
            // a transferência segue pelo evento HandoffRequested do negotiation (proposta HANDED_OFF)
            if (!t.debtorNotified()) {
                sendQuietly(ctx, ctx.policy.settings().handoffMessage());
            }
            ctx.session.handedOff(t.reason(), Instant.now(clock));
            ctx.end(TurnOutcome.HANDED_OFF, t.reason(), t.source());
            return;
        }
        handoff(ctx, t.reason(), t.source(), t.note(), t.referenceType(), t.referenceId(), t.debtorNotified());
    }

    // ------------------------------------------------------------------ modos sombra e copiloto

    private void draft(TurnContext ctx, List<MessageView> history) {
        PolicySettings s = ctx.policy.settings();
        HandoffDetector.first(ctx.inputText, s.extraTriggers()).ifPresent(d -> ctx.alert = d.reason().name());
        loadCaseFacts(ctx);
        Reply reply = converse(ctx, history);
        if (reply.terminal() != null) {
            ctx.errorCode = reply.terminal().reason().name();
            ctx.end(TurnOutcome.SKIPPED, null, reply.terminal().source());
            return;
        }
        ctx.outputText = reply.text();
        ResponseGuard.Result guard = ResponseGuard.check(reply.text(), ctx.memory.facts(),
                new ResponseGuard.Config(s.forbiddenTopics(), s.allowCreditBureauMention()));
        if (!guard.approved()) {
            ctx.guardrails.addAll(guard.violations());
            ctx.end(TurnOutcome.GUARDRAIL_BLOCKED, null, TriggerSource.GUARDRAIL);
            return;
        }
        ctx.draftId = channel.draft(ctx, reply.text(), rationale(ctx)).id();
        ctx.end(TurnOutcome.DRAFTED, null, null);
    }

    /** Justificativa curta para o operador, sem dados pessoais (até 500). */
    private String rationale(TurnContext ctx) {
        StringBuilder sb = new StringBuilder(ctx.mode == AssistantMode.COPILOT ? "Copiloto" : "Rascunho do assistente (modo sombra)");
        if (ctx.alert != null) {
            HandoffReason reason = HandoffReason.valueOf(ctx.alert);
            String profile = ctx.policy.settings().requiredProfileFor(reason);
            sb.append(" | Alerta: ").append(reason.name()).append(" — avalie encaminhar")
                    .append(profile == null ? "" : " (perfil " + profile + ")");
        }
        if (ctx.mode == AssistantMode.COPILOT && !ctx.memory.offers().isEmpty()) {
            SessionMemory.OfferMemo best = ctx.memory.offers().stream().filter(SessionMemory.OfferMemo::withinAuthority)
                    .max(Comparator.comparingInt(SessionMemory.OfferMemo::level)).orElse(ctx.memory.offers().getFirst());
            sb.append(" | Próxima oferta sugerida: ").append(best.installments()).append(" pagamento(s), total R$ ")
                    .append(best.total().toPlainString()).append(", nível ").append(best.level());
        }
        sb.append(" | Confirme a identidade do titular antes de enviar valores.");
        return sb.length() <= 500 ? sb.toString() : sb.substring(0, 500);
    }

    // ------------------------------------------------------------------ modelo

    private Reply converse(TurnContext ctx, List<MessageView> history) {
        LanguageModelPort model = models.getIfAvailable();
        if (model == null) {
            throw new LanguageModelPort.ModelUnavailableException(
                    "Nenhum provedor de IA configurado (escolha pendente — issue #12)", null);
        }
        PolicySettings s = ctx.policy.settings();
        PolicySettings.Limits limits = s.limits();
        ctx.prompt = prompts.require(s.promptCode(), s.promptVersion());
        ctx.provider = model.provider();
        List<ModelMessage> conversation = modelMessages(ctx, history);
        LocalDate today = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE));
        for (int round = 0; round <= limits.maxToolCallsPerTurn(); round++) {
            if (ctx.inputTokens + ctx.outputTokens >= limits.maxTokensPerTurn()) {
                return new Reply(null, new Terminal(TurnOutcome.HANDED_OFF, HandoffReason.COST_LIMIT, TriggerSource.LIMIT,
                        "limite de tokens do turno", null, null, false, false));
            }
            ModelContext context = ContextBuilder.build(s, ctx.session, ctx.memory, ctx.debtorName, ctx.caseDaysOverdue,
                    ctx.caseStage, ctx.conversation.channel(), today, ctx.mode.drafts());
            ctx.lastContext = codec.toJson(context);
            ctx.lastContextHash = AssistantCodec.sha256(ctx.lastContext);
            ModelRequest request = new ModelRequest(ctx.prompt.text(), ctx.lastContext, conversation,
                    toolbox.available(ctx), limits.maxOutputTokens(), Duration.ofSeconds(limits.timeoutSeconds()),
                    Map.of("turn", ctx.token.toString(), "tenant", ctx.tenantId.toString()));
            ModelResponse response = call(ctx, model, request);
            if (!response.toolCalls().isEmpty()) {
                conversation.add(ModelMessage.assistant(response.text(), response.toolCalls()));
                for (ToolCall call : response.toolCalls()) {
                    ToolOutcome outcome = ctx.toolCalls.size() >= limits.maxToolCallsPerTurn()
                            ? limitReached(ctx, call) : toolbox.execute(ctx, call);
                    conversation.add(ModelMessage.tool(call.id(), outcome.resultJson()));
                    if (outcome.terminal() != null) {
                        return new Reply(null, outcome.terminal());
                    }
                }
                continue;
            }
            if (response.stopReason() == LanguageModelPort.StopReason.CONTENT_FILTERED) {
                ctx.guardrails.add("PROVIDER_FILTERED");
                return new Reply(null, new Terminal(TurnOutcome.HANDED_OFF, HandoffReason.GUARDRAIL,
                        TriggerSource.GUARDRAIL, "resposta filtrada pelo provedor", null, null, false, false));
            }
            return new Reply(response.text() == null ? "" : response.text().trim(), null);
        }
        return new Reply(null, new Terminal(TurnOutcome.HANDED_OFF, HandoffReason.TECHNICAL_FAILURE, TriggerSource.LIMIT,
                "limite de chamadas de ferramenta sem resposta final", null, null, false, false));
    }

    private ModelResponse call(TurnContext ctx, LanguageModelPort model, ModelRequest request) {
        long started = System.nanoTime();
        try {
            ModelResponse response = model.complete(request);
            Duration latency = response.latency() != null ? response.latency()
                    : Duration.ofNanos(System.nanoTime() - started);
            ctx.modelCalls++;
            ctx.modelLatencyMs += latency.toMillis();
            ctx.model = response.model();
            if (response.usage() != null) {
                ctx.inputTokens += response.usage().inputTokens();
                ctx.outputTokens += response.usage().outputTokens();
                metrics.tokens(model.provider(), response.usage().inputTokens(), response.usage().outputTokens());
            }
            if (response.cost() != null && response.cost().amount() != null) {
                ctx.cost = ctx.cost.add(response.cost().amount());
                ctx.costCurrency = response.cost().currency();
                metrics.cost(model.provider(), response.cost().currency(), response.cost().amount());
            }
            metrics.modelCall(model.provider(), "ok", latency);
            return response;
        } catch (ModelException e) {
            ctx.modelCalls++;
            metrics.modelCall(model.provider(), e.code(), Duration.ofNanos(System.nanoTime() - started));
            throw e;
        }
    }

    private ToolOutcome limitReached(TurnContext ctx, ToolCall call) {
        String result = codec.toJson(Map.of("error", "tool_call_limit"));
        ctx.toolCalls.add(new TurnContext.ToolCallLog(ctx.nextToolSequence(), call.name(), "{}", result,
                ToolCallOutcome.REJECTED, "tool_call_limit", null, null, 0));
        metrics.tool(call.name(), ToolCallOutcome.REJECTED.name());
        return new ToolOutcome(ToolCallOutcome.REJECTED, result, "tool_call_limit", null, null, null);
    }

    /**
     * Histórico enviado ao modelo: as mensagens mais recentes, minimizadas. Mensagens do devedor anteriores
     * à confirmação de identidade (inclusive a que a confirmou) passam pela minimização estrita.
     */
    private List<ModelMessage> modelMessages(TurnContext ctx, List<MessageView> history) {
        List<ModelMessage> out = new ArrayList<>();
        Instant verifiedAt = ctx.session.getIdentityVerifiedAt();
        boolean confirmed = ctx.mode.drafts() || ctx.session.identityConfirmed();
        for (MessageView m : history) {
            if (m.content() == null || m.content().isBlank()) {
                continue;
            }
            if ("INBOUND".equals(m.direction())) {
                boolean strict = !confirmed || (verifiedAt != null && !receivedAt(m).isAfter(verifiedAt))
                        || ctx.session.getIdentityStatus() == IdentityStatus.PENDING;
                out.add(ModelMessage.user(PersonalDataRedactor.redact(m.content(), strict && !ctx.mode.drafts())));
            } else {
                out.add(ModelMessage.assistant(PersonalDataRedactor.redact(m.content(), false), List.of()));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ transferência

    /**
     * Transfere para humanos: mensagem de transferência ao devedor (salvo opt-out ou já avisado), pedido ao
     * communication com motivo, resumo sem dados pessoais e perfil exigido.
     */
    private void handoff(TurnContext ctx, HandoffReason reason, TriggerSource source, String note, String referenceType,
                         UUID referenceId, boolean debtorNotified) {
        if (reason.notifiesDebtor() && !debtorNotified && ctx.hasPolicy()) {
            sendQuietly(ctx, ctx.policy.settings().handoffMessage());
        }
        channel.handoff(ctx, reason, summary(ctx, reason, source, note), referenceType, referenceId);
        ctx.session.handedOff(reason, Instant.now(clock));
        ctx.end(TurnOutcome.HANDED_OFF, reason, source);
    }

    private void fail(TurnContext ctx, RuntimeException e, boolean transfer) {
        ctx.errorCode = e instanceof ModelException me ? me.code()
                : e instanceof DomainException de ? de.code() : e.getClass().getSimpleName();
        LOG.atError().addKeyValue("conversationId", ctx.conversation.id()).addKeyValue("errorCode", ctx.errorCode)
                .setCause(e).log("Falha no turno do assistente; conversa transferida para humanos");
        if (transfer && "ASSISTANT".equals(ctx.conversation.ownerType())) {
            try {
                boolean notified = ctx.hasPolicy() && sendQuietly(ctx, ctx.policy.settings().fallbackMessage());
                handoff(ctx, HandoffReason.TECHNICAL_FAILURE, TriggerSource.FAILURE, ctx.errorCode, null, null, notified);
            } catch (RuntimeException handoffFailure) {
                LOG.atError().addKeyValue("conversationId", ctx.conversation.id()).setCause(handoffFailure)
                        .log("Transferência após falha do assistente também falhou");
            }
        }
        ctx.end(TurnOutcome.FAILED, ctx.handoffReason, TriggerSource.FAILURE);
    }

    private void recover(UUID tenantId, ConversationView c, PolicyLookup.Effective policy, AssistantSession session) {
        UUID interrupted = session.abandonPending();
        AssistantMode mode = policy == null ? AssistantMode.OFF : policy.settings().mode();
        TurnContext ctx = new TurnContext(tenantId, c, policy, mode,
                policy == null ? null : actors.forPolicy(tenantId, policy.policy(), mode, c.creditorId()), interrupted,
                Instant.now(clock));
        // a sessão já sem a marca do turno interrompido; o registro usa a mesma marca para concluir
        session.begin(interrupted, null, Instant.now(clock), policy == null ? null : policy.policy().getId());
        ctx.session = session;
        ctx.memory = codec.memory(session.getMemory());
        ctx.errorCode = "INTERRUPTED";
        metrics.recovered();
        if ("ASSISTANT".equals(c.ownerType())) {
            try {
                boolean notified = policy != null && sendQuietly(ctx, policy.settings().fallbackMessage());
                handoff(ctx, HandoffReason.TECHNICAL_FAILURE, TriggerSource.FAILURE, "turno interrompido", null, null,
                        notified);
            } catch (RuntimeException e) {
                LOG.atError().addKeyValue("conversationId", c.id()).setCause(e)
                        .log("Transferência do turno interrompido falhou");
            }
        }
        ctx.end(TurnOutcome.FAILED, ctx.handoffReason, TriggerSource.FAILURE);
        store.record(ctx);
    }

    /** Resumo para o operador, sem dados pessoais: motivo, estado e o que o motor devolveu. */
    private String summary(TurnContext ctx, HandoffReason reason, TriggerSource source, String note) {
        StringBuilder sb = new StringBuilder("Transferência do assistente virtual: ").append(reason.name())
                .append(" (detectado por ").append(source == null ? "NA" : source.name()).append(").");
        if (ctx.hasPolicy()) {
            sb.append(" Política ").append(ctx.policy.policy().getCode()).append(" v")
                    .append(ctx.policy.policy().getPolicyVersion()).append(", modo ").append(ctx.mode.name()).append('.');
        }
        sb.append(" Turno ").append(ctx.session.getTurns() + 1).append(" da etapa ").append(ctx.session.getEpisode())
                .append(". Identidade: ").append(ctx.session.getIdentityStatus().name()).append('.');
        if (ctx.intent != null) {
            sb.append(" Intenção: ").append(ctx.intent).append('.');
        }
        SessionMemory m = ctx.memory;
        if (m.debt() != null) {
            sb.append(" Saldo atualizado: R$ ").append(m.debt().updatedTotal().toPlainString()).append('.');
        }
        if (!m.offers().isEmpty()) {
            SessionMemory.OfferMemo last = m.offers().getLast();
            sb.append(" Última oferta: ").append(last.installments()).append(" pagamento(s), total R$ ")
                    .append(last.total().toPlainString()).append(", nível ").append(last.level())
                    .append(last.withinAuthority() ? "" : " (acima da alçada)").append('.');
        }
        if (m.proposal() != null) {
            sb.append(" Proposta ").append(m.proposal().proposalId()).append(" (").append(m.proposal().status()).append(").");
        }
        if (note != null && !note.isBlank()) {
            sb.append(" Observação: ").append(PersonalDataRedactor.redact(note, true));
        }
        return sb.length() <= 2000 ? sb.toString() : sb.substring(0, 2000);
    }

    // ------------------------------------------------------------------ apoio

    /** @return a mensagem saiu do porteiro (não foi negada) */
    private boolean sendQuietly(TurnContext ctx, String text) {
        try {
            Optional<MessageView> sent = channel.send(ctx, text);
            sent.ifPresent(m -> {
                if (ctx.outboundMessageId == null) {
                    ctx.outboundMessageId = m.id();
                }
            });
            return sent.map(m -> !"FAILED".equals(m.status())).orElse(false);
        } catch (DomainException e) {
            LOG.atWarn().addKeyValue("conversationId", ctx.conversation.id()).addKeyValue("code", e.code())
                    .log("Mensagem do assistente recusada pelo communication");
            return false;
        }
    }

    private void registerOptOut(TurnContext ctx) {
        try {
            channel.optOut(ctx);
        } catch (DomainException e) {
            LOG.atWarn().addKeyValue("conversationId", ctx.conversation.id()).addKeyValue("code", e.code())
                    .log("Opt-out pedido na conversa não registrado pelo compliance");
            ctx.errorCode = e.code();
        }
    }

    /** Primeiro nome e situação do caso (só o necessário vai ao contexto do modelo). */
    private void loadCaseFacts(TurnContext ctx) {
        ctx.debtorName = actors.as(ctx.actor, () -> debtors.get(ctx.conversation.debtorId())).name();
        if (ctx.conversation.caseId() != null) {
            CaseUseCases.CaseView kase = actors.as(ctx.actor, () -> cases.get(ctx.conversation.caseId()));
            ctx.caseDaysOverdue = kase.daysOverdue();
            ctx.caseStage = kase.status();
        }
    }

    private List<MessageView> history(UUID conversationId) {
        List<MessageView> recent = new ArrayList<>(messages.listByConversation(conversationId,
                PageQuery.of(0, properties.historyMessages())).content());
        recent.sort(Comparator.comparing(TurnOrchestrator::receivedAt));
        return recent;
    }

    /** Mensagens do devedor ainda não tratadas (depois da última tratada ou da última mensagem de saída). */
    private static List<MessageView> unprocessed(List<MessageView> history, AssistantSession session) {
        Instant cutoff = session.getLastProcessedInboundAt();
        if (cutoff == null) {
            cutoff = history.stream().filter(m -> "OUTBOUND".equals(m.direction())).map(TurnOrchestrator::receivedAt)
                    .max(Comparator.naturalOrder()).orElse(Instant.EPOCH);
        }
        Instant after = cutoff;
        return history.stream().filter(m -> "INBOUND".equals(m.direction()))
                .filter(m -> m.content() != null && !m.content().isBlank())
                .filter(m -> receivedAt(m).isAfter(after)).toList();
    }

    private static Instant receivedAt(MessageView m) {
        return m.receivedAt() != null ? m.receivedAt() : m.createdAt();
    }
}
