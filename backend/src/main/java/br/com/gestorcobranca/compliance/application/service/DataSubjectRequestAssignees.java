package br.com.gestorcobranca.compliance.application.service;

import br.com.gestorcobranca.compliance.application.port.in.DataSubjectRequestAssigneeUseCases;
import br.com.gestorcobranca.identity.application.port.in.UserDirectory;
import br.com.gestorcobranca.identity.application.port.in.UserDirectory.UserSummary;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.paging.NameSearch;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Responsável por solicitação de titular: um único predicado de elegibilidade ({@link #isEligible}) usado pela lista de
 * usuários atribuíveis ({@link #search}) e pela gravação ({@link #requireEligible}, chamada por
 * {@code DataSubjectRequestService.assign} e {@code start}). Elegível = usuário ativo, com
 * {@code DATA_SUBJECT_REQUEST_MANAGE} e acesso a todos os credores — o mesmo alcance que os comandos da solicitação
 * exigem de quem atua. A lista devolve só id e nome e cada consulta vai para o log de acesso
 * ({@link AccessEventType#USER_DIRECTORY_QUERIED}) com o recurso e a quantidade devolvida — nunca o texto buscado.
 */
@Service
class DataSubjectRequestAssignees implements DataSubjectRequestAssigneeUseCases {

    static final String RESOURCE = "data_subject_request_assignee";

    private final UserDirectory users;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AccessLogger accessLogger;

    DataSubjectRequestAssignees(UserDirectory users, AccessScope accessScope, CurrentActor currentActor,
                                AccessLogger accessLogger) {
        this.users = users;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.accessLogger = accessLogger;
    }

    /** Regra única de elegibilidade (lista e gravação). */
    static boolean isEligible(UserSummary user) {
        return user != null && user.active() && user.allCreditors()
                && user.permissions().contains(Permission.DATA_SUBJECT_REQUEST_MANAGE.name());
    }

    /** Recusa a gravação de responsável inexistente ou inelegível, com a mesma regra da lista. */
    void requireEligible(UUID userId) {
        Guard.require(userId != null, "dsr.assignee_required", "Responsável obrigatório");
        UserSummary user = users.find(userId).orElseThrow(() -> new BusinessRuleException("dsr.assignee_invalid",
                "Responsável não encontrado"));
        Guard.require(isEligible(user), "dsr.assignee_invalid",
                "O responsável precisa estar ativo, atender solicitações de titulares e ter acesso a todos os credores");
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<AssigneeView> search(String query, Set<UUID> ids, PageQuery page) {
        accessScope.requirePermission(Permission.DATA_SUBJECT_REQUEST_MANAGE);
        accessScope.requireAllCreditors(RESOURCE);
        Guard.require(ids == null || ids.size() <= MAX_IDS, "dsr_assignee.ids_limit",
                "Informe no máximo %d usuários por consulta".formatted(MAX_IDS));
        List<UserSummary> eligible = users.activeUsersWithPermission(Permission.DATA_SUBJECT_REQUEST_MANAGE.name()).stream()
                .filter(DataSubjectRequestAssignees::isEligible).toList();
        PageResponse<AssigneeView> result = NameSearch.page(eligible, UserSummary::id, UserSummary::name, query, ids, page,
                MAX_PAGE_SIZE, u -> new AssigneeView(u.id(), u.name()));
        accessLogger.log(AccessEvent.of(AccessEventType.USER_DIRECTORY_QUERIED, currentActor.get(),
                Map.of("resource", RESOURCE, "returned", String.valueOf(result.content().size()))));
        return result;
    }
}
