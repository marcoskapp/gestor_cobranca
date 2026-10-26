package br.com.gestorcobranca.helpdesk.application.service;

import br.com.gestorcobranca.helpdesk.application.HelpdeskProperties;
import br.com.gestorcobranca.helpdesk.application.HelpdeskProperties.Limits;
import br.com.gestorcobranca.helpdesk.application.port.in.HelpdeskChatUseCases;
import br.com.gestorcobranca.helpdesk.application.port.in.HelpdeskExceptions;
import br.com.gestorcobranca.helpdesk.application.port.in.HelpdeskTicketUseCases.TicketView;
import br.com.gestorcobranca.helpdesk.application.port.out.HelpdeskModelPort;
import br.com.gestorcobranca.helpdesk.application.port.out.HelpdeskPrompt;
import br.com.gestorcobranca.helpdesk.application.port.out.KnowledgeBase;
import br.com.gestorcobranca.helpdesk.application.port.out.KnowledgeBase.Screen;
import br.com.gestorcobranca.helpdesk.domain.model.AnswerOutcome;
import br.com.gestorcobranca.helpdesk.domain.model.ChatConversation;
import br.com.gestorcobranca.helpdesk.domain.model.ChatMessage;
import br.com.gestorcobranca.helpdesk.domain.model.ChatMessage.AnswerUsage;
import br.com.gestorcobranca.helpdesk.domain.model.Feedback;
import br.com.gestorcobranca.helpdesk.domain.model.MessageRole;
import br.com.gestorcobranca.helpdesk.domain.model.QuestionTopic;
import br.com.gestorcobranca.helpdesk.domain.repository.ChatMessageRepository.UsageTotals;
import br.com.gestorcobranca.helpdesk.domain.service.PersonalData;
import br.com.gestorcobranca.helpdesk.domain.service.PersonalData.Placeholders;
import br.com.gestorcobranca.helpdesk.domain.service.ReferenceBook;
import br.com.gestorcobranca.identity.application.port.in.AuthenticationUseCases.AuthenticatedUserView;
import br.com.gestorcobranca.identity.application.port.in.MyAccountUseCases;
import br.com.gestorcobranca.shared.ai.LanguageModel.ModelException;
import br.com.gestorcobranca.shared.ai.LanguageModel.ModelMessage;
import br.com.gestorcobranca.shared.ai.LanguageModel.ModelRequest;
import br.com.gestorcobranca.shared.ai.LanguageModel.ModelResponse;
import br.com.gestorcobranca.shared.ai.LanguageModel.StopReason;
import br.com.gestorcobranca.shared.ai.LanguageModel.ToolCall;
import br.com.gestorcobranca.shared.ai.LanguageModel.ToolDefinition;
import br.com.gestorcobranca.shared.ai.ModelTier;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Orquestração de uma pergunta ao chat de suporte (docs/tecnico/desenho/chat-suporte.md §3):
 * <ol>
 *   <li>validação, provedor ligado (sem provedor: {@code helpdesk.unavailable}), limites de taxa, do dia e da
 *       conversa — tudo antes do modelo;</li>
 *   <li>pergunta gravada (cifrada); contexto montado no servidor a partir da base de conhecimento (a tela vem só como
 *       chave validada) e do perfil de quem pergunta;</li>
 *   <li>conversa com o modelo em rodadas de ferramentas somente leitura executadas como o usuário, respeitando
 *       tokens, ferramentas, rodadas, prazo por chamada e prazo total;</li>
 *   <li>resposta mascarada (documento, e-mail, telefone, CEP), gravada com o consumo e o evento de negócio.</li>
 * </ol>
 * Falha do provedor não quebra o chat: a resposta diz que houve instabilidade e a pergunta fica registrada como
 * {@code FAILED}. Logs técnicos sem conteúdo da conversa.
 */
@Service
class HelpdeskChatService implements HelpdeskChatUseCases {

    static final int MAX_QUESTION = 2000;
    static final int MAX_ANSWER = 4000;
    static final int HISTORY_MESSAGES = 12;
    static final String FAILURE_TEXT = "Não consegui responder agora por uma instabilidade no chat de suporte. "
            + "Tente de novo em instantes.";
    static final String FILTERED_TEXT = "Não posso ajudar com esse pedido. Posso ajudar com o uso do Gestor de Cobrança.";
    static final String INCOMPLETE_TEXT = "Não consegui concluir a consulta dentro do limite desta pergunta. "
            + "Tente perguntar de forma mais específica.";
    private static final Pattern SCREEN_KEY = Pattern.compile("[a-z0-9]+(?:[.-][a-z0-9]+)*");
    private static final Logger LOG = LoggerFactory.getLogger(HelpdeskChatService.class);

    private final HelpdeskProperties properties;
    private final ObjectProvider<HelpdeskModelPort> models;
    private final KnowledgeBase knowledge;
    private final HelpdeskPrompt prompts;
    private final ChatStore store;
    private final HelpdeskToolbox toolbox;
    private final ChatContextBuilder contextBuilder;
    private final HelpdeskRateLimiter rateLimiter;
    private final HelpdeskMetrics metrics;
    private final HelpdeskTicketService tickets;
    private final CurrentActor currentActor;
    private final MyAccountUseCases myAccount;
    private final JsonCodec json;
    private final Clock clock;

    HelpdeskChatService(HelpdeskProperties properties, ObjectProvider<HelpdeskModelPort> models, KnowledgeBase knowledge,
                        HelpdeskPrompt prompts, ChatStore store, HelpdeskToolbox toolbox, ChatContextBuilder contextBuilder,
                        HelpdeskRateLimiter rateLimiter, HelpdeskMetrics metrics, HelpdeskTicketService tickets,
                        CurrentActor currentActor, MyAccountUseCases myAccount, JsonCodec json, Clock clock) {
        this.properties = properties;
        this.models = models;
        this.knowledge = knowledge;
        this.prompts = prompts;
        this.store = store;
        this.toolbox = toolbox;
        this.contextBuilder = contextBuilder;
        this.rateLimiter = rateLimiter;
        this.metrics = metrics;
        this.tickets = tickets;
        this.currentActor = currentActor;
        this.myAccount = myAccount;
        this.json = json;
        this.clock = clock;
    }

    /** Conteúdo gravado (cifrado) de cada mensagem. */
    record StoredContent(String text, List<CardView> cards, List<ActionView> actions) {

        StoredContent {
            cards = cards == null ? List.of() : List.copyOf(cards);
            actions = actions == null ? List.of() : List.copyOf(actions);
        }
    }

    @Override
    public StatusView status() {
        Actor actor = currentActor.get();
        UsageTotals today = store.usageToday(actor.id());
        boolean tickets = this.tickets.available();
        return new StatusView(models.getIfAvailable() != null, tickets, tickets ? null : "NOT_CONFIGURED",
                (int) today.questions(), properties.limits().maxQuestionsPerUserPerDay(), knowledge.contentVersion());
    }

    @Override
    public AnswerView ask(AskCommand command) {
        long started = System.nanoTime();
        Actor actor = currentActor.get();
        String text = command.text() == null ? "" : command.text().strip();
        Guard.require(!text.isEmpty(), "helpdesk.question_empty", "Escreva a pergunta");
        Guard.require(text.length() <= MAX_QUESTION, "helpdesk.question_too_long",
                "A pergunta pode ter até " + MAX_QUESTION + " caracteres");
        HelpdeskModelPort model = models.getIfAvailable();
        if (model == null) {
            metrics.unavailable();
            throw new HelpdeskExceptions.Unavailable("helpdesk.unavailable",
                    "O chat de suporte não está disponível neste ambiente. Use o manual (menu Ajuda) ou procure o "
                            + "administrador da assessoria.");
        }
        Limits limits = properties.limits();
        if (!rateLimiter.tryAcquire(actor.id())) {
            throw limit("helpdesk.limit_rate", "Muitas perguntas em pouco tempo. Aguarde um minuto e tente de novo.");
        }
        UsageTotals today = store.usageToday(actor.id());
        if (today.questions() >= limits.maxQuestionsPerUserPerDay()
                || today.costAmount().compareTo(limits.maxCostPerUserPerDay()) >= 0) {
            throw limit("helpdesk.limit_daily", "Você atingiu o limite de uso do chat de hoje. Amanhã ele volta; "
                    + "até lá, use o manual (menu Ajuda).");
        }
        Screen screen = screen(command.screen());
        ChatConversation conversation = store.conversationFor(command.conversationId(), actor,
                screen == null ? null : screen.id());
        if (conversation.getTurns() >= limits.maxTurnsPerConversation()
                || conversation.getCostAmount().compareTo(limits.maxCostPerConversation()) >= 0) {
            throw limit("helpdesk.limit_conversation", "Esta conversa chegou ao limite. Comece uma nova conversa.");
        }
        ChatMessage question = store.appendQuestion(conversation.getId(), actor, json.toJson(
                new StoredContent(text, null, null)), screen == null ? null : screen.id());
        List<ChatMessage> history = store.history(conversation.getId());
        TurnState state = state(actor, conversation, screen, history);

        Turn turn = run(model, state, history, conversation, question.getId(), started);
        String answerText = turn.text;
        AnswerOutcome outcome = turn.outcome;
        StoredContent content = new StoredContent(answerText, state.cards, state.actions);
        long durationMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        ChatMessage answer = store.appendAnswer(conversation.getId(), actor, json.toJson(content),
                screen == null ? null : screen.id(), screen == null ? null : screen.module(), outcome, state.topic,
                turn.usage(prompts.current(), knowledge.contentVersion(), model.provider(), state.toolCalls),
                json.toJson(state.references.toMap()), state.ticket == null ? null : state.ticket.id(), durationMs);
        metrics.turn(model.provider(), outcome.name(), Duration.ofMillis(durationMs));
        metrics.question(state.topic == null ? "UNKNOWN" : state.topic.name(), outcome.name(),
                screen == null ? null : screen.id());
        LOG.atInfo().addKeyValue("conversationId", conversation.getId()).addKeyValue("outcome", outcome)
                .addKeyValue("modelCalls", turn.modelCalls).addKeyValue("toolCalls", state.toolCalls)
                .addKeyValue("durationMs", durationMs).addKeyValue("errorCode", turn.errorCode)
                .log("Pergunta do chat de suporte respondida");
        return new AnswerView(conversation.getId(), view(question, null), view(answer, state.ticket));
    }

    // ------------------------------------------------------------------ rodadas com o modelo

    /** Acumulado de uma pergunta. */
    private static final class Turn {
        String text;
        AnswerOutcome outcome;
        String errorCode;
        String model;
        int modelCalls;
        long input;
        long output;
        long cacheRead;
        long cacheWrite;
        BigDecimal cost = BigDecimal.ZERO;
        String currency;
        long modelLatencyMs;

        void add(ModelResponse r) {
            modelCalls++;
            if (r.usage() != null) {
                input += r.usage().promptTokens();
                output += r.usage().outputTokens();
                cacheRead += r.usage().cacheReadInputTokens();
                cacheWrite += r.usage().cacheWriteInputTokens();
            }
            if (r.cost() != null && r.cost().amount() != null) {
                cost = cost.add(r.cost().amount());
                currency = r.cost().currency();
            }
            if (r.latency() != null) {
                modelLatencyMs += r.latency().toMillis();
            }
            model = r.model();
        }

        AnswerUsage usage(HelpdeskPrompt.Prompt prompt, String knowledgeVersion, String provider, int toolCalls) {
            return new AnswerUsage(prompt.reference(), prompt.sha256(), knowledgeVersion, provider, model, modelCalls,
                    toolCalls, input, output, cacheRead, cacheWrite, cost, currency, modelLatencyMs, errorCode);
        }
    }

    private Turn run(HelpdeskModelPort model, TurnState state, List<ChatMessage> history, ChatConversation conversation,
                     UUID questionId, long startedNanos) {
        Limits limits = properties.limits();
        HelpdeskPrompt.Prompt prompt = prompts.current();
        List<ToolDefinition> tools = toolbox.available(state);
        List<ModelMessage> messages = new ArrayList<>(modelHistory(history, state.placeholders));
        long deadline = startedNanos + limits.turnTimeout().toNanos();
        Map<String, String> metadata = Map.of("product", "helpdesk", "message", questionId.toString(), "tenant",
                state.actor.tenantId().toString());
        Turn turn = new Turn();
        for (int round = 1; round <= limits.maxRounds(); round++) {
            Duration remaining = Duration.ofNanos(deadline - System.nanoTime());
            if (remaining.compareTo(Duration.ofSeconds(2)) < 0 || turn.input + turn.output >= limits.maxTokensPerTurn()
                    || conversation.getCostAmount().add(turn.cost).compareTo(limits.maxCostPerConversation()) >= 0) {
                return finish(turn, INCOMPLETE_TEXT, AnswerOutcome.FAILED, "TURN_LIMIT");
            }
            String context = json.toJson(contextBuilder.build(state, tools));
            ModelRequest request = new ModelRequest(prompt.text(), context, messages, tools, limits.maxOutputTokens(),
                    min(limits.callTimeout(), remaining), metadata, ModelTier.STANDARD);
            ModelResponse response;
            long callStarted = System.nanoTime();
            try {
                response = model.complete(request);
            } catch (ModelException e) {
                metrics.modelCall(model.provider(), e.code(), Duration.ofNanos(System.nanoTime() - callStarted));
                LOG.atWarn().addKeyValue("conversationId", state.conversationId).addKeyValue("errorCode", e.code())
                        .log("Modelo do chat de suporte indisponível");
                return finish(turn, FAILURE_TEXT, AnswerOutcome.FAILED, e.code());
            }
            turn.add(response);
            metrics.modelCall(model.provider(), "ok", response.latency() == null ? Duration.ZERO : response.latency());
            record(model.provider(), response);
            if (response.stopReason() == StopReason.CONTENT_FILTERED) {
                state.topic = state.topic == null ? QuestionTopic.OUT_OF_SCOPE : state.topic;
                return finish(turn, FILTERED_TEXT, AnswerOutcome.OUT_OF_SCOPE, "PROVIDER_FILTERED");
            }
            if (response.toolCalls().isEmpty()) {
                return finish(turn, response.text(), outcome(state), null);
            }
            messages.add(ModelMessage.assistant(response.text(), response.toolCalls()));
            for (ToolCall call : response.toolCalls()) {
                String result;
                if (state.toolCalls >= limits.maxToolCalls()) {
                    result = "{\"error\":\"limite_de_ferramentas\",\"mensagem\":\"Responda com o que já tem\"}";
                } else {
                    state.toolCalls++;
                    result = toolbox.execute(call, state);
                }
                messages.add(ModelMessage.tool(call.id(), result));
            }
        }
        return finish(turn, INCOMPLETE_TEXT, AnswerOutcome.FAILED, "ROUND_LIMIT");
    }

    private Turn finish(Turn turn, String text, AnswerOutcome outcome, String errorCode) {
        String clean = text == null ? "" : PersonalData.mask(text.strip());
        if (clean.isEmpty()) {
            clean = INCOMPLETE_TEXT;
            outcome = AnswerOutcome.FAILED;
            errorCode = errorCode == null ? "EMPTY_ANSWER" : errorCode;
        }
        turn.text = clean.length() <= MAX_ANSWER ? clean : clean.substring(0, MAX_ANSWER - 1) + "…";
        turn.outcome = outcome;
        turn.errorCode = errorCode;
        return turn;
    }

    private static AnswerOutcome outcome(TurnState state) {
        if (state.topic == QuestionTopic.OUT_OF_SCOPE) {
            return AnswerOutcome.OUT_OF_SCOPE;
        }
        return Boolean.FALSE.equals(state.resolved) ? AnswerOutcome.UNANSWERED : AnswerOutcome.ANSWERED;
    }

    private void record(String provider, ModelResponse r) {
        if (r.usage() != null) {
            metrics.tokens(provider, "input", r.usage().inputTokens());
            metrics.tokens(provider, "output", r.usage().outputTokens());
            metrics.tokens(provider, "cache_read", r.usage().cacheReadInputTokens());
            metrics.tokens(provider, "cache_write", r.usage().cacheWriteInputTokens());
        }
        if (r.cost() != null) {
            metrics.cost(provider, r.cost().currency(), r.cost().amount());
        }
    }

    /** Histórico recente para o modelo: documentos como [DOCn], e-mail/telefone/CEP mascarados. */
    private List<ModelMessage> modelHistory(List<ChatMessage> history, Placeholders placeholders) {
        List<ModelMessage> out = new ArrayList<>();
        int from = Math.max(0, history.size() - HISTORY_MESSAGES);
        for (int i = from; i < history.size(); i++) {
            ChatMessage m = history.get(i);
            String text = placeholders.texts().get(i);
            if (m.purged() || text == null) {
                continue;
            }
            if (m.getRole() == MessageRole.USER) {
                out.add(ModelMessage.user(text));
            } else {
                out.add(ModelMessage.assistant(text, List.of()));
            }
        }
        return out;
    }

    private TurnState state(Actor actor, ChatConversation conversation, Screen screen, List<ChatMessage> history) {
        List<String> texts = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (ChatMessage m : history) {
            StoredContent c = content(m);
            texts.add(c == null ? null : c.text());
            if (c != null) {
                c.cards().stream().filter(card -> "DEBTOR".equals(card.kind()) && card.title() != null)
                        .forEach(card -> names.add(card.title()));
            }
        }
        // documentos digitados pela pessoa viram [DOCn]; respostas do chat já saem mascaradas
        Placeholders placeholders = PersonalData.withPlaceholders(texts);
        ReferenceBook references = ReferenceBook.of(json.toMap(conversation.getReferencesJson()));
        TurnState state = new TurnState(actor, conversation.getId(), screen, references, placeholders, profile(actor));
        state.shownNames.addAll(names);
        state.lastUserText = texts.isEmpty() ? null : texts.getLast();
        return state;
    }

    private TurnState.UserProfile profile(Actor actor) {
        AuthenticatedUserView me = myAccount.profile();
        Map<String, String> labels = knowledge.profileLabels();
        List<String> roleLabels = me.roles().stream().sorted()
                .map(code -> "PLATFORM_ADMIN".equals(code) ? labels.getOrDefault("PLATAFORMA", code)
                        : labels.getOrDefault(code, code))
                .toList();
        Set<String> permissions = new LinkedHashSet<>();
        actor.permissions().forEach(p -> permissions.add(p.name()));
        return new TurnState.UserProfile(me.name(), me.email(), me.tenantName(), me.roles(), roleLabels,
                Set.copyOf(permissions), actor.creditorScope().all());
    }

    private Screen screen(String key) {
        if (key == null || key.length() > 64 || !SCREEN_KEY.matcher(key).matches()) {
            return null;
        }
        return knowledge.screen(key).orElse(null);
    }

    private HelpdeskExceptions.LimitReached limit(String code, String message) {
        metrics.limit(code);
        return new HelpdeskExceptions.LimitReached(code, message, Map.of());
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }

    // ------------------------------------------------------------------ histórico e avaliação

    @Override
    public ConversationView conversation(UUID conversationId) {
        Actor actor = currentActor.get();
        ChatConversation c = store.owned(conversationId, actor);
        List<MessageView> messages = store.history(conversationId).stream()
                .map(m -> view(m, m.getTicketId() == null ? null : ticket(m.getTicketId()))).toList();
        return new ConversationView(c.getId(), c.getStartedAt(), c.getLastActivityAt(), c.getTurns(), messages);
    }

    @Override
    public List<ConversationSummary> recent(int limit) {
        Actor actor = currentActor.get();
        return store.recent(actor).stream().limit(Math.max(1, Math.min(limit, 20)))
                .map(c -> new ConversationSummary(c.getId(), c.getStartedAt(), c.getLastActivityAt(), c.getTurns(),
                        c.getScreenKey()))
                .toList();
    }

    @Override
    public MessageView feedback(UUID messageId, FeedbackCommand command) {
        Actor actor = currentActor.get();
        Feedback rating;
        try {
            rating = Feedback.valueOf(Objects.requireNonNullElse(command.rating(), ""));
        } catch (IllegalArgumentException e) {
            throw new br.com.gestorcobranca.shared.domain.BusinessRuleException("helpdesk.feedback_invalid",
                    "Avaliação deve ser UP ou DOWN");
        }
        String reason = command.reason() == null || command.reason().isBlank() ? null : command.reason().trim();
        ChatMessage m = store.feedback(messageId, actor, rating, reason);
        metrics.feedback(rating.name(), m.getScreenKey());
        return view(m, m.getTicketId() == null ? null : ticket(m.getTicketId()));
    }

    private TicketView ticket(UUID id) {
        try {
            return tickets.get(id);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private StoredContent content(ChatMessage m) {
        if (m.getContent() == null) {
            return null;
        }
        try {
            return json.fromJson(m.getContent(), new TypeReference<StoredContent>() { });
        } catch (RuntimeException e) {
            return new StoredContent(m.getContent(), null, null);
        }
    }

    private MessageView view(ChatMessage m, TicketView ticket) {
        StoredContent c = content(m);
        return new MessageView(m.getId(), m.getRole().name(), c == null ? null : c.text(),
                c == null ? List.of() : c.cards(), c == null ? List.of() : c.actions(), ticket,
                m.getOutcome() == null ? null : m.getOutcome().name(),
                m.getFeedback() == null ? null : m.getFeedback().name(), m.purged(), m.getOccurredAt());
    }
}
