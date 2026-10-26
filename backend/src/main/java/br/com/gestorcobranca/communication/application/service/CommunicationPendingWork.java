package br.com.gestorcobranca.communication.application.service;

import br.com.gestorcobranca.communication.application.port.out.CommunicationPendingWorkQueries;
import br.com.gestorcobranca.communication.domain.model.ChannelType;
import br.com.gestorcobranca.communication.domain.service.ResponseTargets;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkItem;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkRequest;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkSource;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pendências da comunicação: conversas do próprio operador e da fila aguardando resposta (vencidas =
 * além da meta de primeira resposta do canal, a mesma do SLA da caixa de entrada) e rascunhos de
 * template aguardando o registro da decisão do provedor ou a publicação.
 */
@Component
class CommunicationPendingWork implements PendingWorkSource {

    static final String MY_CONVERSATIONS = "communication.my_conversations";
    static final String QUEUE_CONVERSATIONS = "communication.queue_conversations";
    static final String TEMPLATES = "communication.template_approval";

    private final CommunicationPendingWorkQueries queries;
    private final ResponseTargetProvider responseTargets;
    private final AccessScope accessScope;

    CommunicationPendingWork(CommunicationPendingWorkQueries queries, ResponseTargetProvider responseTargets,
                             AccessScope accessScope) {
        this.queries = queries;
        this.responseTargets = responseTargets;
        this.accessScope = accessScope;
    }

    @Override
    public String id() {
        return "communication";
    }

    @Override
    public String label() {
        return "Conversas";
    }

    @Override
    public boolean appliesTo(Actor actor) {
        return actor.has(Permission.INBOX_VIEW) || actor.has(Permission.TEMPLATE_MANAGE);
    }

    @Override
    public List<PendingWorkItem> pendingFor(PendingWorkRequest request) {
        Actor actor = request.actor();
        Set<UUID> scope = accessScope.hasAllCreditors() ? null : accessScope.restrictCreditors(List.of());
        List<PendingWorkItem> items = new ArrayList<>();
        if (actor.has(Permission.INBOX_VIEW)) {
            Map<String, Instant> breachedBefore = breachedBefore(responseTargets.current(), request.now());
            items.add(PendingWorkItem.of(MY_CONVERSATIONS, "Suas conversas aguardando resposta",
                            queries.awaitingReply(scope, actor.id(), breachedBefore))
                    .hint("Responda antes de estourar a meta de primeira resposta.")
                    .route("/conversas").param("aba", "mine").param("aguardando", "true")
                    .singleRoute(id -> "/conversas/" + id).build());
            items.add(PendingWorkItem.of(QUEUE_CONVERSATIONS, "Conversas na fila aguardando atendimento",
                            queries.awaitingReply(scope, null, breachedBefore))
                    .hint("Assuma uma conversa da fila da equipe.")
                    .route("/conversas").param("aba", "queue").param("aguardando", "true").build());
        }
        if (actor.has(Permission.TEMPLATE_MANAGE)) {
            items.add(PendingWorkItem.of(TEMPLATES, "Templates aguardando aprovação ou publicação",
                            queries.templatesAwaitingApproval(scope))
                    .hint("Registre a decisão do provedor e publique os aprovados.")
                    .route("/conversas/templates").param("situacao", "DRAFT").build());
        }
        return items;
    }

    private static Map<String, Instant> breachedBefore(ResponseTargets targets, Instant now) {
        Map<String, Instant> limits = new LinkedHashMap<>();
        for (ChannelType channel : ChannelType.values()) {
            limits.put(channel.name(), now.minus(targets.target(channel).breach()));
        }
        return limits;
    }
}
