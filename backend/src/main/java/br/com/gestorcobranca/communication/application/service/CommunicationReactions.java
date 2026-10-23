package br.com.gestorcobranca.communication.application.service;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.collection.application.port.in.CaseWorkUseCases;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases;
import br.com.gestorcobranca.collection.application.port.in.InteractionUseCases.RecordInteractionCommand;
import br.com.gestorcobranca.collection.application.port.in.TabulationUseCases;
import br.com.gestorcobranca.communication.domain.model.Conversation;
import br.com.gestorcobranca.communication.domain.model.HandoffKind;
import br.com.gestorcobranca.communication.domain.repository.ConversationRepository;
import br.com.gestorcobranca.shared.events.collection.CaseAssignmentChanged;
import br.com.gestorcobranca.shared.events.collection.CaseStatusChanged;
import br.com.gestorcobranca.shared.events.communication.HandoffRequested;
import br.com.gestorcobranca.shared.events.communication.MessageStatusChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Reações a eventos de integração (executadas por {@code CommunicationEventListeners}, no tenant do
 * evento, como sistema, idempotentes por evento):
 * <ul>
 *   <li>{@code MessageStatusChanged} (do próprio módulo) → interação no caso pelo port público do
 *       collection, após o commit do envio/recebimento (falha aqui não desfaz nem repete o envio);</li>
 *   <li>{@code HandoffRequested} → transferência das conversas do caso para humanos;</li>
 *   <li>{@code CaseAssignmentChanged} → alinha o dono das conversas abertas do caso;</li>
 *   <li>{@code CaseStatusChanged} (abertura) → vincula conversas abertas do devedor no credor sem caso.</li>
 * </ul>
 */
@Component
class CommunicationReactions {

    private static final Logger LOG = LoggerFactory.getLogger(CommunicationReactions.class);
    static final String SENT_TABULATION = "MESSAGE_SENT";
    static final String RECEIVED_TABULATION = "MESSAGE_RECEIVED";
    /** Limite do collection para registrar interações retroativas. */
    private static final Duration MAX_BACKDATE = Duration.ofDays(7);

    private final ConversationRepository conversations;
    private final ConversationOwnership ownership;
    private final CaseReader cases;
    private final CaseWorkUseCases work;
    private final InteractionUseCases interactions;
    private final TabulationUseCases tabulations;
    private final CommunicationMetrics metrics;
    private final Clock clock;

    CommunicationReactions(ConversationRepository conversations, ConversationOwnership ownership, CaseReader cases,
                           CaseWorkUseCases work, InteractionUseCases interactions, TabulationUseCases tabulations,
                           CommunicationMetrics metrics, Clock clock) {
        this.conversations = conversations;
        this.ownership = ownership;
        this.cases = cases;
        this.work = work;
        this.interactions = interactions;
        this.tabulations = tabulations;
        this.metrics = metrics;
        this.clock = clock;
    }

    void messageStatusChanged(MessageStatusChanged e) {
        metrics.eventConsumed("message_status");
        boolean sent = "OUTBOUND".equals(e.direction()) && "SENT".equals(e.status());
        boolean received = "INBOUND".equals(e.direction()) && "RECEIVED".equals(e.status());
        if (!sent && !received) {
            return;
        }
        String direction = sent ? "OUTBOUND" : "INBOUND";
        Optional<Conversation> conversation = conversations.findById(e.conversationId());
        if (e.caseId() == null || conversation.isEmpty()) {
            metrics.interaction(direction, "skipped_no_case");
            return;
        }
        CaseView kase = cases.get(e.caseId());
        if ("CLOSED".equals(kase.status())) {
            metrics.interaction(direction, "skipped_case_closed");
            return;
        }
        if (e.occurredAt().isBefore(Instant.now(clock).minus(MAX_BACKDATE))) {
            metrics.interaction(direction, "skipped_too_old");
            return;
        }
        String code = sent ? SENT_TABULATION : RECEIVED_TABULATION;
        boolean available = tabulations.list(true).stream().anyMatch(t -> code.equals(t.code()));
        if (!available) {
            LOG.atWarn().addKeyValue("tabulation", code)
                    .log("Tabulação de mensagem desativada ou inexistente; interação não registrada no caso");
            metrics.interaction(direction, "skipped_tabulation");
            return;
        }
        var result = interactions.record(e.caseId(), new RecordInteractionCommand(e.channel(), direction, null, code, null,
                conversation.get().getContactId(), e.occurredAt(), null, null, null, null, "COMMUNICATION",
                "message:" + e.messageId()));
        metrics.interaction(direction, result.duplicate() ? "duplicate" : "recorded");
    }

    void handoffRequested(HandoffRequested e) {
        metrics.eventConsumed("handoff_requested");
        List<Conversation> targets = e.conversationId() != null
                ? conversations.findById(e.conversationId()).filter(Conversation::isOpen).stream().toList()
                : conversations.findOpenByCaseId(e.caseId());
        String reason = e.reasonCode() == null || e.reasonCode().isBlank() ? "UNSPECIFIED"
                : e.reasonCode().trim().toUpperCase(Locale.ROOT);
        if (targets.isEmpty()) {
            // sem conversa: o pedido vale para o caso (devolução aos humanos se o assistente o conduz)
            if (e.caseId() != null) {
                CaseView kase = cases.get(e.caseId());
                if (!"CLOSED".equals(kase.status()) && "ASSISTANT".equals(kase.ownerType())) {
                    work.release(kase.id(), "Transferência para humano: " + reason);
                }
            }
            return;
        }
        String summary = e.summary() == null || e.summary().isBlank()
                ? "Transferência solicitada (%s)%s".formatted(reason,
                e.referenceType() == null ? "" : " — " + e.referenceType() + " " + e.referenceId())
                : e.summary();
        String profile = e.requiredProfile() == null || e.requiredProfile().isBlank() ? null
                : e.requiredProfile().trim().toUpperCase(Locale.ROOT);
        ConversationOwnership.Change change = new ConversationOwnership.Change(HandoffKind.ESCALATION, reason, summary,
                summary.length() > 2000 ? summary.substring(0, 2000) : summary, profile, e.referenceType(), e.referenceId());
        // a transferência alcança as demais conversas abertas do mesmo caso
        Conversation first = conversations.findByIdForUpdate(targets.getFirst().getId()).orElseThrow();
        ownership.escalate(first, change);
        for (Conversation other : targets.subList(1, targets.size())) {
            if (other.getCaseId() == null || !other.getCaseId().equals(first.getCaseId())) {
                ownership.escalate(conversations.findByIdForUpdate(other.getId()).orElseThrow(), change);
            }
        }
    }

    void caseAssignmentChanged(CaseAssignmentChanged e) {
        metrics.eventConsumed("case_assignment");
        syncCase(e.caseId());
    }

    void caseStatusChanged(CaseStatusChanged e) {
        metrics.eventConsumed("case_status");
        if (e.previousStatus() != null || "CLOSED".equals(e.newStatus())) {
            return;
        }
        // caso aberto: conversas abertas do devedor no credor sem caso passam a pertencer a ele
        CaseView kase = null;
        for (Conversation c : conversations.findOpenWithoutCase(e.debtorId(), e.creditorId())) {
            if (kase == null) {
                kase = cases.get(e.caseId());
            }
            Conversation locked = conversations.findByIdForUpdate(c.getId()).orElseThrow();
            locked.linkCase(e.debtorId(), e.portfolioId(), e.caseId());
            ownership.syncFromCase(conversations.save(locked), kase, locked.getAwaitingSince() != null);
        }
    }

    private void syncCase(java.util.UUID caseId) {
        List<Conversation> open = conversations.findOpenByCaseId(caseId);
        if (open.isEmpty()) {
            return;
        }
        CaseView kase = cases.get(caseId);
        if ("CLOSED".equals(kase.status())) {
            return;
        }
        for (Conversation c : open) {
            Conversation locked = conversations.findByIdForUpdate(c.getId()).orElseThrow();
            ownership.syncFromCase(locked, kase, locked.getAwaitingSince() != null);
        }
    }
}
