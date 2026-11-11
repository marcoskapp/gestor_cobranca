package br.com.gestorcobranca.communication.application.service;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.communication.application.port.in.ConversationAssigneeUseCases;
import br.com.gestorcobranca.communication.domain.model.Conversation;
import br.com.gestorcobranca.identity.application.port.in.UserDirectory.UserSummary;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.paging.NameSearch;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Operadores atribuíveis a uma conversa: mesma regra ({@link ConversationAssignees#isEligible}) e mesmo contexto (caso
 * ativo ou credor da conversa) que a gravação usa; cada consulta vai para o log de acesso
 * ({@link AccessEventType#USER_DIRECTORY_QUERIED}) com a conversa e a quantidade devolvida — nunca o texto buscado.
 */
@Service
@Transactional(readOnly = true)
class ConversationAssigneeService implements ConversationAssigneeUseCases {

    private static final String RESOURCE = "conversation_assignee";

    private final ConversationAccess access;
    private final ConversationAssignees assignees;
    private final CaseReader cases;
    private final AccessScope accessScope;
    private final AccessLogger accessLogger;

    ConversationAssigneeService(ConversationAccess access, ConversationAssignees assignees, CaseReader cases,
                                AccessScope accessScope, AccessLogger accessLogger) {
        this.access = access;
        this.assignees = assignees;
        this.cases = cases;
        this.accessScope = accessScope;
        this.accessLogger = accessLogger;
    }

    @Override
    public PageResponse<AssigneeView> search(UUID conversationId, String query, Set<UUID> ids, PageQuery page) {
        if (access.actor().isUser()) {
            accessScope.requirePermission(Permission.INBOX_MANAGE);
        }
        Guard.require(ids == null || ids.size() <= MAX_IDS, "conversation_assignee.ids_limit",
                "Informe no máximo %d usuários por consulta".formatted(MAX_IDS));
        Conversation c = access.visible(conversationId);
        Guard.requireState(c.isOpen(), "conversation.closed", "Conversa encerrada");
        Optional<CaseView> kase = cases.active(c.getCaseId());
        UUID creditorId = kase.map(CaseView::creditorId).orElse(c.getCreditorId());
        boolean withCase = kase.isPresent();
        List<UserSummary> eligible = assignees.candidates().stream()
                .filter(u -> ConversationAssignees.isEligible(u, creditorId, withCase)).toList();
        PageResponse<AssigneeView> result = NameSearch.page(eligible, UserSummary::id, UserSummary::name, query, ids, page,
                MAX_PAGE_SIZE, u -> new AssigneeView(u.id(), u.name()));

        Map<String, String> details = new LinkedHashMap<>();
        details.put("resource", RESOURCE);
        details.put("conversationId", c.getId().toString());
        details.put("withCase", String.valueOf(withCase));
        details.put("returned", String.valueOf(result.content().size()));
        accessLogger.log(AccessEvent.of(AccessEventType.USER_DIRECTORY_QUERIED, access.actor(), details));
        return result;
    }
}
