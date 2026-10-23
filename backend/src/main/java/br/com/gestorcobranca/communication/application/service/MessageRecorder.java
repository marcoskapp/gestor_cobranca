package br.com.gestorcobranca.communication.application.service;

import br.com.gestorcobranca.communication.domain.model.Conversation;
import br.com.gestorcobranca.communication.domain.model.Message;
import br.com.gestorcobranca.communication.domain.model.MessageDirection;
import br.com.gestorcobranca.communication.domain.model.MessageStatus;
import br.com.gestorcobranca.communication.domain.model.MessageTemplate;
import br.com.gestorcobranca.communication.domain.repository.MessageTemplateRepository;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.events.communication.MessageStatusChanged;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Efeitos de cada mudança de status de mensagem, na transação da mudança: evento de integração
 * {@code MessageStatusChanged} (com autor e carteira, usados pelo incentive), evento de negócio
 * {@code message.*} (sem conteúdo nem contato) e métricas {@code communication.*}.
 */
@Component
class MessageRecorder {

    private final ApplicationEventPublisher events;
    private final BusinessEventLogger businessEvents;
    private final CommunicationMetrics metrics;
    private final MessageTemplateRepository templates;
    private final CurrentActor currentActor;

    MessageRecorder(ApplicationEventPublisher events, BusinessEventLogger businessEvents, CommunicationMetrics metrics,
                    MessageTemplateRepository templates, CurrentActor currentActor) {
        this.events = events;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.templates = templates;
        this.currentActor = currentActor;
    }

    void queued(Message m, Conversation c) {
        count(m);
        publish(m, c);
    }

    /** Negado pelo porteiro de contato. @param stage {@code precheck} (pedido) ou {@code send} (job) */
    void blocked(Message m, Conversation c, List<String> reasons, String stage) {
        count(m);
        reasons.forEach(r -> metrics.blocked(m.getChannel().name(), m.getSenderType().name(), stage, r));
        businessEvents.log(base(BusinessEventTypes.MESSAGE_BLOCKED, m, c)
                .attr("stage", stage)
                .attr("reasons", String.join(",", reasons)));
        publish(m, c);
    }

    void sent(Message m, Conversation c, String provider) {
        count(m);
        metrics.sendAttempt(m.getChannel().name(), provider, "accepted");
        Duration latency = Duration.between(m.getQueuedAt(), m.getSentAt());
        metrics.sendLatency(m.getChannel().name(), latency);
        businessEvents.log(base(BusinessEventTypes.MESSAGE_SENT, m, c)
                .attr("provider", provider)
                .attr("attempts", m.getSendAttempts())
                .durationMs(Math.max(0, latency.toMillis())));
        publish(m, c);
    }

    void failed(Message m, Conversation c) {
        count(m);
        businessEvents.log(base(BusinessEventTypes.MESSAGE_FAILED, m, c)
                .attr("failureCode", m.getFailureCode())
                .attr("attempts", m.getSendAttempts()));
        publish(m, c);
    }

    /** Status informado pelo provedor (DELIVERED, READ ou FAILED). */
    void providerStatus(Message m, Conversation c) {
        if (m.getStatus() == MessageStatus.FAILED) {
            failed(m, c);
            return;
        }
        count(m);
        String type = m.getStatus() == MessageStatus.READ ? BusinessEventTypes.MESSAGE_READ : BusinessEventTypes.MESSAGE_DELIVERED;
        BusinessEvent event = base(type, m, c);
        if (m.getSentAt() != null) {
            Instant reference = m.getStatus() == MessageStatus.READ ? m.getReadAt() : m.getDeliveredAt();
            Duration since = Duration.between(m.getSentAt(), reference);
            event.durationMs(Math.max(0, since.toMillis()));
            if (m.getStatus() == MessageStatus.DELIVERED) {
                metrics.deliveryLatency(m.getChannel().name(), since.isNegative() ? Duration.ZERO : since);
            }
        }
        businessEvents.log(event);
        publish(m, c);
    }

    /** Mensagem recebida do devedor. @param reply havia mensagem enviada antes na conversa */
    void received(Message m, Conversation c, boolean reply, String contentType, Optional<String> optOutKeyword) {
        count(m);
        businessEvents.log(base(reply ? BusinessEventTypes.MESSAGE_REPLIED : BusinessEventTypes.MESSAGE_RECEIVED, m, c)
                .attr("contentType", contentType)
                .attr("identified", c.getDebtorId() != null)
                .attr("optOut", optOutKeyword.isPresent()));
        publish(m, c);
    }

    private void count(Message m) {
        metrics.messageStatus(m.getChannel().name(), m.getDirection().name(), m.getSenderType().name(),
                m.getStatus().name());
    }

    private BusinessEvent base(String type, Message m, Conversation c) {
        BusinessEvent event = BusinessEvent.of(type)
                .creditor(c.getCreditorId())
                .portfolio(c.getPortfolioId())
                .debtor(c.getDebtorId())
                .collectionCase(c.getCaseId())
                .reference("message", m.getId())
                .channel(m.getChannel().name())
                .attr("conversationId", c.getId().toString())
                .attr("direction", m.getDirection())
                .attr("actorType", m.getSenderType())
                .attr("ownerType", c.getOwnerType());
        if (m.getCampaignExecutionId() != null) {
            event.attr("campaignExecutionId", m.getCampaignExecutionId().toString());
        }
        if (m.getTemplateId() != null) {
            templates.findById(m.getTemplateId()).ifPresent((MessageTemplate t) -> event
                    .attr("templateCode", t.getCode())
                    .attr("templateVersion", t.getTemplateVersion())
                    .attr("purpose", t.getPurpose()));
        }
        return event;
    }

    private void publish(Message m, Conversation c) {
        boolean outbound = m.getDirection() == MessageDirection.OUTBOUND;
        events.publishEvent(new MessageStatusChanged(Ids.newId(), currentActor.tenantId(), m.getStatusAt(), m.getId(),
                c.getId(), c.getDebtorId(), c.getCreditorId(), c.getCaseId(), m.getCampaignExecutionId(),
                m.getChannel().name(), m.getDirection().name(), m.getStatus().name(),
                outbound ? m.getSenderType().name() : null, outbound ? m.getSenderId() : null, c.getPortfolioId()));
    }
}
