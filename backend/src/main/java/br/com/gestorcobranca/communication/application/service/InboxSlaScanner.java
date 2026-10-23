package br.com.gestorcobranca.communication.application.service;

import br.com.gestorcobranca.communication.domain.model.ChannelType;
import br.com.gestorcobranca.communication.domain.model.Conversation;
import br.com.gestorcobranca.communication.domain.model.SlaScanCursor;
import br.com.gestorcobranca.communication.domain.repository.ConversationRepository;
import br.com.gestorcobranca.communication.domain.repository.SlaScanCursorRepository;
import br.com.gestorcobranca.communication.domain.service.ResponseTargets;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Detecta, na assessoria corrente, as conversas que cruzaram o limite de primeira resposta sem
 * resposta desde a varredura anterior: cada uma gera uma única vez o evento de negócio
 * {@code conversation.sla_breached} e a métrica {@code communication.inbox.sla.breaches} (alarme).
 * O cursor por canal fica no banco e avança na mesma transação dos eventos.
 */
@Component
@Transactional
class InboxSlaScanner {

    /** Máximo de conversas por canal e execução (o restante fica para o ciclo seguinte). */
    static final int BATCH = 500;
    /** Atraso máximo recuperado após indisponibilidade prolongada da varredura. */
    static final Duration MAX_CATCH_UP = Duration.ofHours(24);

    private final ConversationRepository conversations;
    private final SlaScanCursorRepository cursors;
    private final ResponseTargetProvider targets;
    private final BusinessEventLogger businessEvents;
    private final CommunicationMetrics metrics;
    private final Clock clock;

    InboxSlaScanner(ConversationRepository conversations, SlaScanCursorRepository cursors, ResponseTargetProvider targets,
                    BusinessEventLogger businessEvents, CommunicationMetrics metrics, Clock clock) {
        this.conversations = conversations;
        this.cursors = cursors;
        this.targets = targets;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** @return conversas com limite estourado detectadas nesta execução */
    int scan() {
        Instant now = Instant.now(clock);
        ResponseTargets current = targets.current();
        int found = 0;
        for (ChannelType channel : ChannelType.values()) {
            Duration breach = current.target(channel).breach();
            // primeira execução na assessoria: conta só o que estourar daqui em diante
            SlaScanCursor cursor = cursors.findByChannel(channel).orElseGet(() -> new SlaScanCursor(channel, now));
            Instant from = cursor.getScannedUntil().isBefore(now.minus(MAX_CATCH_UP)) ? now.minus(MAX_CATCH_UP)
                    : cursor.getScannedUntil();
            List<Conversation> breached = conversations.findAwaitingBetween(channel, from.minus(breach),
                    now.minus(breach), BATCH);
            for (Conversation c : breached) {
                metrics.slaBreached(channel.name(), c.getOwnerType().name());
                businessEvents.log(BusinessEvent.of(BusinessEventTypes.CONVERSATION_SLA_BREACHED)
                        .creditor(c.getCreditorId())
                        .portfolio(c.getPortfolioId())
                        .debtor(c.getDebtorId())
                        .collectionCase(c.getCaseId())
                        .reference("conversation", c.getId())
                        .channel(channel.name())
                        .attr("owner", c.getOwnerType())
                        .attr("ownerUserId", c.getOwnerUserId() == null ? null : c.getOwnerUserId().toString())
                        .attr("requiredProfile", c.getRequiredProfile())
                        .attr("breachMinutes", breach.toMinutes())
                        .durationMs(breach.toMillis()));
            }
            found += breached.size();
            cursor.advanceTo(breached.size() == BATCH ? breached.getLast().getAwaitingSince().plus(breach) : now);
            cursors.save(cursor);
        }
        return found;
    }
}
