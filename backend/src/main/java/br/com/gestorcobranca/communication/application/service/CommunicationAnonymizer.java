package br.com.gestorcobranca.communication.application.service;

import br.com.gestorcobranca.communication.domain.model.Conversation;
import br.com.gestorcobranca.communication.domain.model.ConversationDraft;
import br.com.gestorcobranca.communication.domain.model.ConversationHandoff;
import br.com.gestorcobranca.communication.domain.model.ConversationStatus;
import br.com.gestorcobranca.communication.domain.model.Message;
import br.com.gestorcobranca.communication.domain.model.MessageStatus;
import br.com.gestorcobranca.communication.domain.repository.ConversationDraftRepository;
import br.com.gestorcobranca.communication.domain.repository.ConversationHandoffRepository;
import br.com.gestorcobranca.communication.domain.repository.ConversationRepository;
import br.com.gestorcobranca.communication.domain.repository.MessageRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * LGPD (SEG-20261124-D01): devedor anonimizado ({@code DebtorAnonymized}) — nas conversas dele, o contato sai (valor,
 * ligação ao contato do devedor, máscara e blind index) e a conversa aberta é encerrada; o conteúdo, o assunto e as
 * variáveis das mensagens, o texto e a justificativa dos rascunhos e o resumo das transferências são apagados. Ficam
 * canal, datas, situação e quem atendeu, para os indicadores. Idempotente (o consumidor também é).
 */
@Component
class CommunicationAnonymizer {

    /** Código de falha dos envios pendentes de titular anonimizado. */
    static final String SUBJECT_ANONYMIZED = "SUBJECT_ANONYMIZED";

    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final ConversationDraftRepository drafts;
    private final ConversationHandoffRepository handoffs;
    private final ConversationService conversationService;
    private final MessageRecorder recorder;
    private final CommunicationMetrics metrics;
    private final AuditTrail auditTrail;
    private final Clock clock;

    CommunicationAnonymizer(ConversationRepository conversations, MessageRepository messages,
                            ConversationDraftRepository drafts, ConversationHandoffRepository handoffs,
                            ConversationService conversationService, MessageRecorder recorder,
                            CommunicationMetrics metrics,
                            AuditTrail auditTrail, Clock clock) {
        this.conversations = conversations;
        this.messages = messages;
        this.drafts = drafts;
        this.handoffs = handoffs;
        this.conversationService = conversationService;
        this.recorder = recorder;
        this.metrics = metrics;
        this.auditTrail = auditTrail;
        this.clock = clock;
    }

    void anonymize(UUID debtorId, UUID sourceEventId) {
        Instant now = Instant.now(clock);
        int total = 0;
        int purgedTotal = 0;
        for (Conversation found : conversations.findByDebtorId(debtorId)) {
            Conversation conversation = found.getStatus() == ConversationStatus.OPEN
                    ? conversationService.closeBySystem(found, "Titular anonimizado (LGPD)") : found;
            boolean contact = conversation.anonymizeContact();
            conversations.save(conversation);
            // nada mais sai para o titular: envios pendentes falham antes de o conteúdo ser apagado
            for (Message queued : messages.findByConversationIdAndStatus(conversation.getId(), MessageStatus.QUEUED)) {
                queued.markFailed(SUBJECT_ANONYMIZED, null, now);
                messages.save(queued);
                recorder.failed(queued, conversation);
            }
            // em lote, sem decifrar o conteúdo (como a retenção)
            int purged = messages.purgeContentOfConversation(conversation.getId(), now);
            purgedTotal += purged;
            total++;
            for (ConversationDraft draft : drafts.findByConversationId(conversation.getId())) {
                if (draft.anonymize(now)) {
                    drafts.save(draft);
                }
            }
            for (ConversationHandoff handoff : handoffs.findByConversationIdOrderByOccurredAtAsc(conversation.getId())) {
                if (handoff.anonymizeSummary()) {
                    handoffs.save(handoff);
                }
            }
            auditTrail.record(AuditEntry.of("conversation.anonymized", "conversation", conversation.getId())
                    .creditor(conversation.getCreditorId()).meta("sourceEventId", sourceEventId)
                    .meta("contactRemoved", contact).meta("messagesPurged", purged));
        }
        metrics.eventConsumed("debtor_anonymized");
        auditTrail.record(AuditEntry.of("communication.debtor_anonymized", "debtor", debtorId)
                .meta("sourceEventId", sourceEventId).meta("conversations", total).meta("messagesPurged", purgedTotal));
    }
}
