package br.com.gestorcobranca.incentive.application.service;

import br.com.gestorcobranca.identity.application.port.in.UserDirectory;
import br.com.gestorcobranca.identity.application.port.in.UserDirectory.UserSummary;
import br.com.gestorcobranca.incentive.application.port.in.AssignableUserView;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.paging.NameSearch;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Quem pode ser escolhido nos campos de pessoa do incentivo. Um único predicado ({@link #eligibility}) serve à lista da
 * tela ({@link #search}, {@link #eligibleIds}) e à gravação ({@link #requireEligible}):
 *
 * <ul>
 *   <li>{@link Purpose#EVALUATION} (monitoria e ocorrência de conduta): negociador ({@code CASE_WORK}) habilitado
 *       (ativo ou aguardando ativação; nunca desativado), dentro do escopo de quem registra
 *       ({@link IncentiveAccess#visibleUsers()}: todos, ou os membros das equipes que supervisiona) e nunca a própria
 *       pessoa;</li>
 *   <li>{@link Purpose#GOAL} (meta individual nova): negociador ({@code CASE_WORK}) habilitado e dentro do mesmo
 *       escopo (a própria pessoa pode ter meta).</li>
 * </ul>
 *
 * Só id e nome; cada consulta vai para o log de acesso ({@link AccessEventType#USER_DIRECTORY_QUERIED}) com o campo e a
 * quantidade devolvida — nunca o texto buscado. A permissão do campo é conferida por quem chama.
 */
@Component
class IncentiveAssignees {

    private static final String RESOURCE = "incentive_assignee";

    enum Purpose { EVALUATION, GOAL }

    private final UserDirectory users;
    private final IncentiveAccess access;
    private final AccessLogger accessLogger;

    IncentiveAssignees(UserDirectory users, IncentiveAccess access, AccessLogger accessLogger) {
        this.users = users;
        this.access = access;
        this.accessLogger = accessLogger;
    }

    PageResponse<AssignableUserView> search(Purpose purpose, String query, Set<UUID> ids, PageQuery page) {
        Guard.require(ids == null || ids.size() <= AssignableUserView.MAX_IDS, "incentive_assignee.ids_limit",
                "Informe no máximo %d usuários por consulta".formatted(AssignableUserView.MAX_IDS));
        List<AssignableUserView> candidates = eligible(purpose).stream()
                .map(u -> new AssignableUserView(u.id(), u.name())).toList();
        PageResponse<AssignableUserView> result = NameSearch.page(candidates, AssignableUserView::id,
                AssignableUserView::name, query, ids, page, AssignableUserView.MAX_PAGE_SIZE);
        Map<String, String> details = new LinkedHashMap<>();
        details.put("resource", RESOURCE);
        details.put("purpose", purpose.name());
        details.put("returned", String.valueOf(result.content().size()));
        accessLogger.log(AccessEvent.of(AccessEventType.USER_DIRECTORY_QUERIED, access.actor(), details));
        return result;
    }

    /** Ids elegíveis, ordenados (o escopo de equipe da monitoria). */
    List<UUID> eligibleIds(Purpose purpose) {
        return eligible(purpose).stream().map(UserSummary::id).sorted().toList();
    }

    /** Recusa ao gravar quem a lista do campo não mostraria. */
    UserSummary requireEligible(Purpose purpose, UUID userId, String code, String message) {
        Guard.require(userId != null, code, message);
        return users.find(userId).filter(eligibility(purpose))
                .orElseThrow(() -> new BusinessRuleException(code, message));
    }

    private List<UserSummary> eligible(Purpose purpose) {
        return users.enabledUsers(Permission.CASE_WORK.name()).stream().filter(eligibility(purpose))
                .toList();
    }

    /** Predicado único de elegibilidade por campo. */
    private Predicate<UserSummary> eligibility(Purpose purpose) {
        // sem visão de desempenho, ninguém é elegível (lista vazia e recusa de negócio, nunca 403 na tela)
        Set<UUID> visible = access.visibleUsersOrNone();
        Predicate<UserSummary> negotiator = u -> u.enabled() && u.permissions().contains(Permission.CASE_WORK.name())
                && (visible == null || visible.contains(u.id()));
        return switch (purpose) {
            case EVALUATION -> {
                UUID self = access.actor().id();
                yield negotiator.and(u -> !u.id().equals(self));
            }
            case GOAL -> negotiator;
        };
    }
}
