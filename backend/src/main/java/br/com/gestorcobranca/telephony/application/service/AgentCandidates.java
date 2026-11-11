package br.com.gestorcobranca.telephony.application.service;

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
import br.com.gestorcobranca.telephony.application.port.in.AgentCandidateUseCases;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Usuário do operador na telefonia: um único predicado de elegibilidade ({@link #isEligible}) usado pela lista de
 * usuários habilitáveis ({@link #search}) e pela gravação ({@link #requireEligible}, chamada por
 * {@code AgentService.configure}). Elegível = usuário existente, ativo e com {@code TELEPHONY_USE} (a permissão de
 * entrar na telefonia, ligar e tabular). A lista devolve só id e nome e cada consulta vai para o log de acesso
 * ({@link AccessEventType#USER_DIRECTORY_QUERIED}) com o recurso e a quantidade devolvida — nunca o texto buscado.
 */
@Service
class AgentCandidates implements AgentCandidateUseCases {

    static final String RESOURCE = "telephony_agent_candidate";

    private final UserDirectory users;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AccessLogger accessLogger;

    AgentCandidates(UserDirectory users, AccessScope accessScope, CurrentActor currentActor, AccessLogger accessLogger) {
        this.users = users;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.accessLogger = accessLogger;
    }

    /** Regra única de elegibilidade (lista e gravação). */
    static boolean isEligible(UserSummary user) {
        return user != null && user.active() && user.permissions().contains(Permission.TELEPHONY_USE.name());
    }

    /** Recusa a gravação de operador inexistente ou inelegível, com a mesma regra da lista. */
    void requireEligible(UUID userId) {
        UserSummary user = users.find(userId).orElseThrow(() -> new BusinessRuleException("agent.user_invalid",
                "Usuário não encontrado"));
        Guard.require(isEligible(user), "agent.user_invalid",
                "O usuário precisa estar ativo e ter permissão para usar a telefonia");
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<CandidateView> search(String query, Set<UUID> ids, PageQuery page) {
        accessScope.requirePermission(Permission.TELEPHONY_MANAGE);
        Guard.require(ids == null || ids.size() <= MAX_IDS, "agent_candidate.ids_limit",
                "Informe no máximo %d usuários por consulta".formatted(MAX_IDS));
        List<UserSummary> eligible = users.activeUsersWithPermission(Permission.TELEPHONY_USE.name()).stream()
                .filter(AgentCandidates::isEligible).toList();
        PageResponse<CandidateView> result = NameSearch.page(eligible, UserSummary::id, UserSummary::name, query, ids, page,
                MAX_PAGE_SIZE, u -> new CandidateView(u.id(), u.name()));
        accessLogger.log(AccessEvent.of(AccessEventType.USER_DIRECTORY_QUERIED, currentActor.get(),
                Map.of("resource", RESOURCE, "returned", String.valueOf(result.content().size()))));
        return result;
    }
}
