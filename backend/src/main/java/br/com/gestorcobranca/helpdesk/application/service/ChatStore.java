package br.com.gestorcobranca.helpdesk.application.service;

import br.com.gestorcobranca.helpdesk.domain.model.AnswerOutcome;
import br.com.gestorcobranca.helpdesk.domain.model.ChatConversation;
import br.com.gestorcobranca.helpdesk.domain.model.ChatMessage;
import br.com.gestorcobranca.helpdesk.domain.model.ChatMessage.AnswerUsage;
import br.com.gestorcobranca.helpdesk.domain.model.Feedback;
import br.com.gestorcobranca.helpdesk.domain.model.QuestionTopic;
import br.com.gestorcobranca.helpdesk.domain.repository.ChatConversationRepository;
import br.com.gestorcobranca.helpdesk.domain.repository.ChatMessageRepository;
import br.com.gestorcobranca.helpdesk.domain.repository.ChatMessageRepository.UsageTotals;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.Actor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Gravações do chat em transações curtas: a chamada ao modelo nunca segura transação nem conexão de banco. Os
 * eventos de negócio da pergunta são gravados com a resposta (mesma transação), sem conteúdo da conversa.
 */
@Component
class ChatStore {

    static final String CONVERSATION = "helpdesk_conversation";
    static final String MESSAGE = "helpdesk_message";

    private final ChatConversationRepository conversations;
    private final ChatMessageRepository messages;
    private final BusinessEventLogger businessEvents;
    private final Clock clock;

    ChatStore(ChatConversationRepository conversations, ChatMessageRepository messages,
              BusinessEventLogger businessEvents, Clock clock) {
        this.conversations = conversations;
        this.messages = messages;
        this.businessEvents = businessEvents;
        this.clock = clock;
    }

    /** Conversa do usuário (404 se não for dele) ou uma nova. */
    @Transactional
    ChatConversation conversationFor(UUID conversationId, Actor actor, String screen) {
        if (conversationId == null) {
            return conversations.save(new ChatConversation(actor.id(), screen, clock.instant()));
        }
        return owned(conversationId, actor);
    }

    @Transactional(readOnly = true)
    ChatConversation owned(UUID conversationId, Actor actor) {
        return conversations.findById(conversationId).filter(c -> c.ownedBy(actor.id()))
                .orElseThrow(() -> new ResourceNotFoundException(CONVERSATION, conversationId));
    }

    @Transactional(readOnly = true)
    List<ChatMessage> history(UUID conversationId) {
        return messages.findByConversationIdOrderBySequenceAsc(conversationId);
    }

    @Transactional
    ChatMessage appendQuestion(UUID conversationId, Actor actor, String text, String screen) {
        ChatConversation c = conversations.findByIdForUpdate(conversationId).filter(x -> x.ownedBy(actor.id()))
                .orElseThrow(() -> new ResourceNotFoundException(CONVERSATION, conversationId));
        int sequence = messages.findByConversationIdOrderBySequenceAsc(conversationId).size() + 1;
        Instant now = clock.instant();
        c.touch(screen, now);
        conversations.save(c);
        return messages.save(ChatMessage.question(conversationId, actor.id(), sequence, text, screen, now));
    }

    /**
     * Resposta, consumo da conversa, livro de referências e o evento de negócio da pergunta
     * ({@code helpdesk.question_answered} ou {@code helpdesk.question_unanswered}).
     */
    @Transactional
    ChatMessage appendAnswer(UUID conversationId, Actor actor, String content, String screen, String module,
                             AnswerOutcome outcome, QuestionTopic topic, AnswerUsage usage, String referencesJson,
                             UUID ticketId, long durationMs) {
        ChatConversation c = conversations.findByIdForUpdate(conversationId).filter(x -> x.ownedBy(actor.id()))
                .orElseThrow(() -> new ResourceNotFoundException(CONVERSATION, conversationId));
        int sequence = messages.findByConversationIdOrderBySequenceAsc(conversationId).size() + 1;
        Instant now = clock.instant();
        ChatMessage answer = ChatMessage.answer(conversationId, actor.id(), sequence, content, screen, now, outcome,
                topic, usage);
        if (ticketId != null) {
            answer.linkTicket(ticketId);
        }
        ChatMessage saved = messages.save(answer);
        c.recordTurn(screen, usage == null ? 0 : usage.inputTokens(), usage == null ? 0 : usage.outputTokens(),
                saved.getCostAmount(), usage == null ? null : usage.currency(), now);
        if (referencesJson != null) {
            c.replaceReferences(referencesJson);
        }
        conversations.save(c);
        BusinessEvent event = BusinessEvent.of(outcome == AnswerOutcome.ANSWERED
                        ? BusinessEventTypes.HELPDESK_QUESTION_ANSWERED : BusinessEventTypes.HELPDESK_QUESTION_UNANSWERED)
                .reference(MESSAGE, saved.getId()).durationMs(durationMs)
                .attr("conversationId", conversationId).attr("turn", c.getTurns()).attr("outcome", outcome.name())
                .attr("topic", topic == null ? null : topic.name()).attr("screen", screen).attr("module", module)
                .attr("ticketDrafted", ticketId != null);
        if (usage != null) {
            event = event.attr("toolCalls", usage.toolCalls()).attr("modelCalls", usage.modelCalls())
                    .attr("inputTokens", usage.inputTokens()).attr("outputTokens", usage.outputTokens())
                    .attr("cost", usage.cost()).attr("costCurrency", usage.currency()).attr("model", usage.model())
                    .attr("promptVersion", usage.promptRef()).attr("knowledgeVersion", usage.knowledgeVersion())
                    .attr("errorCode", usage.errorCode());
        }
        businessEvents.log(event);
        return saved;
    }

    /** Consumo do usuário no dia (fuso de operação). */
    @Transactional(readOnly = true)
    UsageTotals usageToday(UUID userId) {
        Instant start = LocalDate.now(clock.withZone(TimeConfig.BUSINESS_ZONE)).atStartOfDay(TimeConfig.BUSINESS_ZONE)
                .toInstant();
        return messages.usageSince(userId, start);
    }

    @Transactional(readOnly = true)
    List<ChatConversation> recent(Actor actor) {
        return conversations.findTop20ByUserIdOrderByLastActivityAtDesc(actor.id());
    }

    @Transactional
    ChatMessage feedback(UUID messageId, Actor actor, Feedback rating, String reason) {
        ChatMessage m = messages.findById(messageId).filter(x -> x.getUserId().equals(actor.id()))
                .orElseThrow(() -> new ResourceNotFoundException(MESSAGE, messageId));
        Guard.require(reason == null || Feedback.DOWN_REASONS.contains(reason), "helpdesk.feedback_reason_invalid",
                "Motivo da avaliação fora do catálogo");
        m.rate(rating, reason, clock.instant());
        ChatMessage saved = messages.save(m);
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.HELPDESK_FEEDBACK_RECORDED).reference(MESSAGE, m.getId())
                .attr("conversationId", m.getConversationId()).attr("rating", rating.name()).attr("reason", reason)
                .attr("screen", m.getScreenKey()).attr("topic", m.getTopic() == null ? null : m.getTopic().name())
                .attr("outcome", m.getOutcome() == null ? null : m.getOutcome().name()));
        return saved;
    }

    /** Retenção: descarta o conteúdo das mensagens anteriores ao instante (lote por assessoria). */
    @Transactional
    int purgeBefore(Instant before, int limit) {
        List<ChatMessage> batch = messages.findContentBefore(before, limit);
        Instant now = clock.instant();
        batch.forEach(m -> {
            m.purgeContent(now);
            messages.save(m);
        });
        return batch.size();
    }
}
