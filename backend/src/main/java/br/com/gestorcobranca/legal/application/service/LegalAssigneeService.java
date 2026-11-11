package br.com.gestorcobranca.legal.application.service;

import br.com.gestorcobranca.identity.application.port.in.UserDirectory;
import br.com.gestorcobranca.identity.application.port.in.UserDirectory.UserSummary;
import br.com.gestorcobranca.legal.application.port.in.LegalAssigneeUseCases;
import br.com.gestorcobranca.legal.domain.model.LawFirmMember;
import br.com.gestorcobranca.legal.domain.model.LegalMatter;
import br.com.gestorcobranca.legal.domain.repository.LawFirmMemberRepository;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
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
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Usuários atribuíveis nos campos do jurídico (decisão 686). Um único predicado de elegibilidade
 * ({@link #eligibility}) serve à lista ({@link #search}) e à gravação ({@link #requireEligible}, usado por
 * {@code LegalMatterService.assign}, {@code DeadlineCommands} e {@code LawFirmService.addMember}): usuário ativo com
 * {@code LEGAL_MANAGE}; no caso, com acesso ao credor; advogado interno sem vínculo com escritório; para o advogado de
 * escritório parceiro, só os colegas do próprio escritório. Só id e nome; cada consulta vai para o log de acesso
 * ({@link AccessEventType#USER_DIRECTORY_QUERIED}) com o campo, o caso e a quantidade devolvida — nunca o texto buscado.
 */
@Service
@Transactional(readOnly = true)
class LegalAssigneeService implements LegalAssigneeUseCases {

    private static final String RESOURCE = "legal_assignee";

    private final UserDirectory users;
    private final LawFirmMemberRepository members;
    private final LegalAccess access;
    private final AccessScope accessScope;
    private final AccessLogger accessLogger;

    LegalAssigneeService(UserDirectory users, LawFirmMemberRepository members, LegalAccess access,
                         AccessScope accessScope, AccessLogger accessLogger) {
        this.users = users;
        this.members = members;
        this.access = access;
        this.accessScope = accessScope;
        this.accessLogger = accessLogger;
    }

    @Override
    public PageResponse<AssigneeView> search(Purpose purpose, UUID matterId, String query, Set<UUID> ids, PageQuery page) {
        accessScope.requirePermission(Permission.LEGAL_MANAGE);
        Guard.require(purpose != null, "legal_assignee.purpose_required", "Informe o campo da busca");
        Guard.require(ids == null || ids.size() <= MAX_IDS, "legal_assignee.ids_limit",
                "Informe no máximo %d usuários por consulta".formatted(MAX_IDS));

        LegalMatter matter = switch (purpose) {
            case MATTER_RESPONSIBLE -> {
                access.requireInternal(RESOURCE);
                yield access.visible(requireMatter(matterId));
            }
            case DEADLINE_RESPONSIBLE -> access.visible(requireMatter(matterId));
            case FIRM_MEMBER -> {
                access.requireInternal(RESOURCE);
                access.requireAllCreditors(RESOURCE);
                yield null;
            }
        };
        Map<UUID, UUID> firmOf = members.findByActiveTrue().stream()
                .collect(Collectors.toMap(LawFirmMember::getUserId, LawFirmMember::getLawFirmId, (a, b) -> a));
        Predicate<UserSummary> eligible = eligibility(purpose, matter, firmOf);

        List<AssigneeView> candidates = users.activeUsersWithPermission(Permission.LEGAL_MANAGE.name()).stream()
                .filter(eligible).map(u -> new AssigneeView(u.id(), u.name())).toList();
        PageResponse<AssigneeView> result = NameSearch.page(candidates, AssigneeView::id, AssigneeView::name, query,
                ids, page, MAX_PAGE_SIZE);

        Map<String, String> details = new LinkedHashMap<>();
        details.put("resource", RESOURCE);
        details.put("purpose", purpose.name());
        if (matter != null) {
            details.put("matterId", matter.getId().toString());
        }
        details.put("returned", String.valueOf(result.content().size()));
        accessLogger.log(AccessEvent.of(AccessEventType.USER_DIRECTORY_QUERIED, access.actor(), details));
        return result;
    }

    /**
     * Recusa ao gravar quem a lista do campo não mostraria — mesmo predicado de {@link #search}. O chamador já validou o
     * acesso ao caso (ou à gestão de escritórios); aqui só se decide se o usuário escolhido é elegível.
     *
     * @param matter caso jurídico (nulo para {@link Purpose#FIRM_MEMBER})
     * @return o usuário elegível
     */
    UserSummary requireEligible(Purpose purpose, LegalMatter matter, UUID userId, String code, String message) {
        Guard.require(userId != null, code, message);
        Map<UUID, UUID> firmOf = members.findByUserIdAndActiveTrue(userId)
                .map(m -> Map.of(m.getUserId(), m.getLawFirmId())).orElse(Map.of());
        return users.find(userId).filter(eligibility(purpose, matter, firmOf))
                .orElseThrow(() -> new BusinessRuleException(code, message));
    }

    /**
     * Predicado único de elegibilidade por campo.
     *
     * @param firmOf escritório de cada usuário vinculado a escritório parceiro (quem não está no mapa é interno)
     */
    private Predicate<UserSummary> eligibility(Purpose purpose, LegalMatter matter, Map<UUID, UUID> firmOf) {
        Predicate<UserSummary> base = u -> u.active() && u.permissions().contains(Permission.LEGAL_MANAGE.name());
        return switch (purpose) {
            case MATTER_RESPONSIBLE -> {
                UUID creditorId = matter.getCreditorId();
                yield base.and(u -> u.canAccessCreditor(creditorId) && !firmOf.containsKey(u.id()));
            }
            case DEADLINE_RESPONSIBLE -> {
                UUID creditorId = matter.getCreditorId();
                LegalAccess.ActorScope scope = access.scope();
                yield scope.external()
                        ? base.and(u -> u.canAccessCreditor(creditorId) && scope.lawFirmId().equals(firmOf.get(u.id())))
                        : base.and(u -> u.canAccessCreditor(creditorId));
            }
            case FIRM_MEMBER -> base.and(u -> !firmOf.containsKey(u.id()));
        };
    }

    private static UUID requireMatter(UUID matterId) {
        Guard.require(matterId != null, "legal_assignee.matter_required", "Informe o caso jurídico");
        return matterId;
    }
}
