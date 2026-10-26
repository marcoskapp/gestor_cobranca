package br.com.gestorcobranca.helpdesk.application.service;

import br.com.gestorcobranca.helpdesk.application.port.in.HelpdeskTicketUseCases.TicketView;
import br.com.gestorcobranca.helpdesk.application.port.out.TicketTracker.CreatedIssue;
import br.com.gestorcobranca.helpdesk.domain.model.SupportTicket;
import br.com.gestorcobranca.helpdesk.domain.model.TicketStatus;
import br.com.gestorcobranca.helpdesk.domain.repository.SupportTicketRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.Actor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Gravações dos chamados em transações curtas e próprias ({@code REQUIRES_NEW}: valem mesmo que quem chama esteja em
 * outra transação — a falha registrada não é desfeita com a exceção): a chamada ao GitHub acontece fora de qualquer
 * transação, entre {@link #begin} (que marca {@code CREATING} sob bloqueio de linha) e {@link #opened}/{@link #failed}.
 */
@Component
class TicketStore {

    static final String RESOURCE = "support_ticket";
    static final Duration DUPLICATE_WINDOW = Duration.ofHours(24);

    private final SupportTicketRepository tickets;
    private final BusinessEventLogger businessEvents;
    private final AuditTrail auditTrail;
    private final AccessLogger accessLogger;
    private final Clock clock;

    TicketStore(SupportTicketRepository tickets, BusinessEventLogger businessEvents, AuditTrail auditTrail,
                AccessLogger accessLogger, Clock clock) {
        this.tickets = tickets;
        this.businessEvents = businessEvents;
        this.auditTrail = auditTrail;
        this.accessLogger = accessLogger;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    SupportTicket draft(SupportTicket ticket) {
        SupportTicket saved = tickets.save(ticket);
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.HELPDESK_TICKET_DRAFTED).reference(RESOURCE, saved.getId())
                .attr("conversationId", saved.getConversationId()).attr("screen", saved.getScreenKey()));
        return saved;
    }

    @Transactional(readOnly = true)
    SupportTicket get(UUID id, Actor actor) {
        return tickets.findById(id).filter(t -> t.ownedBy(actor.id()))
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id));
    }

    /**
     * @return o chamado em {@code CREATING} e se é uma nova tentativa (procurar o marcador), ou o chamado já
     *         resolvido (aberto, duplicado) sem nada a fazer
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Begin begin(UUID id, Actor actor) {
        SupportTicket t = tickets.findByIdForUpdate(id).filter(x -> x.ownedBy(actor.id()))
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id));
        if (t.getStatus() == TicketStatus.OPENED || t.getStatus() == TicketStatus.DUPLICATE) {
            return new Begin(t, false, true);
        }
        Instant now = clock.instant();
        boolean retry = t.startCreation(now);
        var duplicate = tickets.findOpenedDuplicate(t.getUserId(), t.getContentSha256(), now.minus(DUPLICATE_WINDOW),
                t.getId());
        if (duplicate.isPresent()) {
            t.duplicateOf(duplicate.get(), now);
            tickets.save(t);
            businessEvents.log(BusinessEvent.of(BusinessEventTypes.HELPDESK_TICKET_OPENED).reference(RESOURCE, t.getId())
                    .attr("conversationId", t.getConversationId()).attr("screen", t.getScreenKey()).attr("duplicate", true)
                    .attr("duplicateOf", duplicate.get().getId()));
            return new Begin(t, false, true);
        }
        return new Begin(tickets.save(t), retry, false);
    }

    record Begin(SupportTicket ticket, boolean retry, boolean done) {
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    SupportTicket opened(UUID id, CreatedIssue issue, Actor actor, boolean recovered) {
        SupportTicket t = tickets.findByIdForUpdate(id).orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id));
        Instant now = clock.instant();
        t.opened(issue.number(), issue.url(), now);
        SupportTicket saved = tickets.save(t);
        long minutes = Duration.between(t.getDraftedAt(), now).toMinutes();
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.HELPDESK_TICKET_OPENED).reference(RESOURCE, t.getId())
                .attr("conversationId", t.getConversationId()).attr("screen", t.getScreenKey()).attr("duplicate", false)
                .attr("attempts", t.getAttempts()).attr("recovered", recovered).attr("minutesToConfirm", minutes)
                .attr("issueNumber", issue.number()));
        auditTrail.record(AuditEntry.of("helpdesk.ticket_opened", RESOURCE, t.getId())
                .meta("repository", t.getRepository()).meta("issueNumber", issue.number())
                .meta("conversationId", t.getConversationId().toString()));
        accessLogger.log(AccessEvent.of(AccessEventType.SUPPORT_TICKET_OPENED, actor, Map.of("repository",
                t.getRepository(), "issueNumber", String.valueOf(issue.number()), "ticketId", t.getId().toString())));
        return saved;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    SupportTicket failed(UUID id, String code) {
        SupportTicket t = tickets.findByIdForUpdate(id).orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id));
        t.failed(code);
        return tickets.save(t);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    SupportTicket cancel(UUID id, Actor actor) {
        SupportTicket t = tickets.findByIdForUpdate(id).filter(x -> x.ownedBy(actor.id()))
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE, id));
        t.cancel();
        SupportTicket saved = tickets.save(t);
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.HELPDESK_TICKET_CANCELLED).reference(RESOURCE, t.getId())
                .attr("conversationId", t.getConversationId()).attr("screen", t.getScreenKey()));
        return saved;
    }

    /** Expira os rascunhos vencidos (job diário, por assessoria). */
    @Transactional
    int expire(int limit) {
        Instant now = clock.instant();
        List<SupportTicket> pending = tickets.findPendingExpiredBefore(now, limit);
        pending.forEach(t -> {
            t.expire(now);
            tickets.save(t);
        });
        return pending.size();
    }

    static TicketView view(SupportTicket t, List<String> labels) {
        return new TicketView(t.getId(), t.getStatus().name(), t.getTitle(), t.getBody(), labels, t.getRepository(),
                t.getIssueNumber(), t.getIssueUrl(), t.getErrorCode(), t.getDraftedAt(), t.getExpiresAt());
    }
}
