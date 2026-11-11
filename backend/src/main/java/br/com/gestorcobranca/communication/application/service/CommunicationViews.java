package br.com.gestorcobranca.communication.application.service;

import br.com.gestorcobranca.communication.application.port.in.ConversationUseCases.ConversationView;
import br.com.gestorcobranca.communication.application.port.in.ConversationUseCases.DraftView;
import br.com.gestorcobranca.communication.application.port.in.ConversationUseCases.HandoffView;
import br.com.gestorcobranca.communication.application.port.in.MessageUseCases.MessageView;
import br.com.gestorcobranca.communication.domain.model.ChannelType;
import br.com.gestorcobranca.communication.domain.model.Conversation;
import br.com.gestorcobranca.communication.domain.service.ResponseTargets;
import br.com.gestorcobranca.communication.domain.model.ConversationDraft;
import br.com.gestorcobranca.communication.domain.model.ConversationHandoff;
import br.com.gestorcobranca.communication.domain.model.Message;

import java.time.Duration;
import java.time.Instant;

/** Conversão das entidades em views dos casos de uso (nunca expõem entidades). */
final class CommunicationViews {

    private CommunicationViews() {
    }

    static ConversationView conversation(Conversation c, Instant now, ResponseTargets targets) {
        boolean window = c.getChannel() == ChannelType.WHATSAPP ? c.sessionWindowOpen(now) : true;
        Instant windowEnds = c.getChannel() == ChannelType.WHATSAPP && window
                ? c.getLastInboundAt().plus(Conversation.WHATSAPP_SESSION_WINDOW) : null;
        Long waiting = c.getAwaitingSince() == null ? null : Math.max(0, Duration.between(c.getAwaitingSince(), now).toSeconds());
        return new ConversationView(c.getId(), c.getDebtorId(), c.getCreditorId(), c.getPortfolioId(), c.getCaseId(),
                c.getChannel().name(), c.getChannelAccountId(), c.getContactMasked(), c.getStatus().name(),
                c.getOwnerType().name(), c.getOwnerUserId(), c.getOwnerTeamId(), c.getRequiredProfile(), c.getOwnerSince(),
                c.getLastInboundAt(), c.getLastOutboundAt(), c.getLastMessageAt(), c.getAwaitingSince(), waiting,
                c.getUnreadCount(), c.getMessageCount(), window, windowEnds, c.getClosedAt(), c.getCloseReason(),
                c.getCreatedAt(), c.getVersion() == null ? 0 : c.getVersion(),
                targets.status(c.getChannel(), c.getAwaitingSince(), now).map(Enum::name).orElse(null),
                c.getAwaitingSince() == null ? null : c.getAwaitingSince().plus(targets.target(c.getChannel()).warning()),
                c.getAwaitingSince() == null ? null : c.getAwaitingSince().plus(targets.target(c.getChannel()).breach()));
    }

    static HandoffView handoff(ConversationHandoff h) {
        return new HandoffView(h.getId(), h.getConversationId(), h.getCaseId(), h.getFromOwnerType().name(),
                h.getFromUserId(), h.getToOwnerType().name(), h.getToUserId(), h.getToTeamId(), h.getKind().name(),
                h.getReasonCode(), h.getSummary(), h.getRequiredProfile(), h.getActorType(), h.getActorId(),
                h.getReferenceType(), h.getReferenceId(), h.getOccurredAt());
    }

    static DraftView draft(ConversationDraft d) {
        return new DraftView(d.getId(), d.getConversationId(), d.getMode().name(), d.getAuthorType(), d.getAuthorId(),
                d.getContent(), d.getRationale(), d.getStatus().name(), d.getDecidedBy(), d.getDecidedAt(), d.isEdited(),
                d.getMessageId(), d.getCreatedAt());
    }

    /** Texto mostrado no lugar do código de acesso: ele só vai ao provedor, nunca à tela (decisão 1084). */
    static final String HIDDEN_ACCESS_CODE = "Código de acesso ao portal enviado (oculto por segurança).";

    /** Assunto mostrado no lugar do real (o assunto também é renderizado com as variáveis). */
    static final String HIDDEN_ACCESS_CODE_SUBJECT = "Código de acesso ao portal";

    static MessageView message(Message m) {
        // código de acesso: ninguém o lê na conversa (operador, assistente, histórico do devedor) — nem no assunto
        boolean hidden = m.isAuthentication();
        String content = hidden && m.getContent() != null ? HIDDEN_ACCESS_CODE : m.getContent();
        String subject = hidden && m.getSubject() != null ? HIDDEN_ACCESS_CODE_SUBJECT : m.getSubject();
        return new MessageView(m.getId(), m.getConversationId(), m.getChannel().name(), m.getDirection().name(),
                m.getSenderType().name(), m.getSenderId(), m.getTemplateId(), subject, content,
                m.getContentPurgedAt() != null, m.getStatus().name(), m.getStatusAt(), m.getFailureCode(),
                m.getFailureDetail(), m.getSendAttempts(), m.getCampaignExecutionId(), m.getQueuedAt(), m.getSentAt(),
                m.getDeliveredAt(), m.getReadAt(), m.getFailedAt(), m.getReceivedAt(), m.getCreatedAt());
    }
}
