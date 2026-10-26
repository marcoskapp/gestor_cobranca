package br.com.gestorcobranca.helpdesk.application.service;

import br.com.gestorcobranca.helpdesk.application.HelpdeskProperties;
import br.com.gestorcobranca.helpdesk.application.port.in.HelpdeskExceptions;
import br.com.gestorcobranca.helpdesk.application.port.in.HelpdeskTicketUseCases;
import br.com.gestorcobranca.helpdesk.application.port.out.TicketTracker;
import br.com.gestorcobranca.helpdesk.application.port.out.TicketTracker.CreatedIssue;
import br.com.gestorcobranca.helpdesk.application.port.out.TicketTracker.NewIssue;
import br.com.gestorcobranca.helpdesk.application.port.out.TicketTracker.TrackerException;
import br.com.gestorcobranca.helpdesk.domain.model.SupportTicket;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Chamados de suporte: rascunho preparado pela ferramenta {@code abrir_chamado} e abertura da issue somente depois
 * da confirmação da pessoa.
 * <ul>
 *   <li><strong>Idempotência</strong>: confirmar de novo devolve a mesma issue; confirmações simultâneas são
 *       serializadas pelo bloqueio de linha ({@code CREATING}); uma abertura interrompida procura a issue pelo
 *       marcador antes de criar outra; o mesmo texto aberto pela mesma pessoa em 24 h vira {@code DUPLICATE};</li>
 *   <li><strong>Trava de repositório privado</strong> ({@code app.helpdesk.github.require-private-repository},
 *       desligada até a migração do repositório): ligada, a visibilidade é conferida no GitHub antes de cada abertura;</li>
 *   <li>sem token, a abertura fica indisponível ({@code helpdesk.ticket.unavailable}) e o chat continua.</li>
 * </ul>
 */
@Service
class HelpdeskTicketService implements HelpdeskTicketUseCases {

    private static final Logger LOG = LoggerFactory.getLogger(HelpdeskTicketService.class);

    private final TicketStore store;
    private final TicketTracker tracker;
    private final TicketComposer composer;
    private final CurrentActor currentActor;
    private final HelpdeskMetrics metrics;
    private final HelpdeskProperties properties;
    private final Clock clock;

    HelpdeskTicketService(TicketStore store, TicketTracker tracker, TicketComposer composer, CurrentActor currentActor,
                          HelpdeskMetrics metrics, HelpdeskProperties properties, Clock clock) {
        this.store = store;
        this.tracker = tracker;
        this.composer = composer;
        this.currentActor = currentActor;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    boolean available() {
        return tracker.configured();
    }

    /** Rascunho a partir do que a IA escreveu (ferramenta {@code abrir_chamado}); a pessoa confirma depois. */
    TicketView draft(String title, String summary, TurnState state, Collection<String> knownNames) {
        TicketComposer.Composed composed = composer.compose(title, summary, state, knownNames, clock.instant());
        SupportTicket ticket = store.draft(new SupportTicket(state.conversationId, state.actor.id(), composed.title(),
                composed.body(), composed.contentSha256(), state.screen == null ? null : state.screen.id(),
                tracker.repository(), clock.instant(), properties.draftValidity()));
        metrics.ticket("DRAFTED", ticket.getScreenKey());
        return TicketStore.view(ticket, tracker.labels());
    }

    @Override
    public TicketView get(UUID ticketId) {
        return TicketStore.view(store.get(ticketId, currentActor.get()), tracker.labels());
    }

    @Override
    public TicketView confirm(UUID ticketId) {
        Actor actor = currentActor.get();
        if (!tracker.configured()) {
            store.get(ticketId, actor);
            metrics.ticket("UNAVAILABLE", null);
            throw new HelpdeskExceptions.Unavailable("helpdesk.ticket.unavailable",
                    "A abertura de chamados não está configurada neste ambiente. Procure o administrador da assessoria.");
        }
        TicketStore.Begin begin = store.begin(ticketId, actor);
        SupportTicket ticket = begin.ticket();
        if (begin.done()) {
            metrics.ticket(ticket.getStatus().name(), ticket.getScreenKey());
            return TicketStore.view(ticket, tracker.labels());
        }
        try {
            if (tracker.requiresPrivateRepository() && !tracker.repositoryIsPrivate()) {
                store.failed(ticketId, "helpdesk.ticket.repository_not_private");
                metrics.ticket("BLOCKED_PUBLIC_REPOSITORY", ticket.getScreenKey());
                LOG.atWarn().addKeyValue("ticketId", ticketId).addKeyValue("repository", tracker.repository())
                        .log("Chamado não aberto: a trava exige repositório privado e o repositório está público");
                throw new HelpdeskExceptions.Unavailable("helpdesk.ticket.repository_not_private",
                        "O repositório de chamados está público e a configuração exige repositório privado. "
                                + "O chamado não foi aberto; avise o administrador.");
            }
            Optional<CreatedIssue> existing = begin.retry()
                    ? tracker.findByMarker(ticket.marker(), ticket.getDraftedAt()) : Optional.empty();
            CreatedIssue issue = existing.orElseGet(() -> tracker.create(new NewIssue(ticket.getTitle(),
                    ticket.getBody() + "\n<!-- " + ticket.marker() + " -->\n", tracker.labels())));
            SupportTicket opened = store.opened(ticketId, issue, actor, existing.isPresent());
            metrics.ticket("OPENED", opened.getScreenKey());
            LOG.atInfo().addKeyValue("ticketId", ticketId).addKeyValue("issueNumber", issue.number())
                    .addKeyValue("recovered", existing.isPresent()).log("Chamado de suporte aberto");
            return TicketStore.view(opened, tracker.labels());
        } catch (TrackerException e) {
            store.failed(ticketId, "helpdesk.ticket." + e.code());
            metrics.ticket("FAILED", ticket.getScreenKey());
            LOG.atWarn().addKeyValue("ticketId", ticketId).addKeyValue("errorCode", e.code())
                    .addKeyValue("transient", e.transientFailure()).log("Falha ao abrir o chamado no GitHub");
            throw new HelpdeskExceptions.Unavailable("helpdesk.ticket." + e.code(), e.transientFailure()
                    ? "O GitHub não respondeu agora. O chamado continua salvo: tente confirmar de novo em instantes."
                    : "O GitHub recusou a abertura do chamado. O chamado continua salvo; avise o administrador.");
        } catch (RuntimeException e) {
            if (!(e instanceof HelpdeskExceptions.Unavailable)) {
                store.failed(ticketId, "helpdesk.ticket.unexpected");
                metrics.ticket("FAILED", ticket.getScreenKey());
            }
            throw e;
        }
    }

    @Override
    public TicketView cancel(UUID ticketId) {
        SupportTicket t = store.cancel(ticketId, currentActor.get());
        metrics.ticket("CANCELLED", t.getScreenKey());
        return TicketStore.view(t, tracker.labels());
    }
}
