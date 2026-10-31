package br.com.gestorcobranca.legal.application.service;

import br.com.gestorcobranca.identity.application.port.in.UserDirectory;
import br.com.gestorcobranca.identity.application.port.in.UserDirectory.UserSummary;
import br.com.gestorcobranca.legal.application.port.in.LegalAssigneeUseCases;
import br.com.gestorcobranca.legal.domain.model.LawFirmMember;
import br.com.gestorcobranca.legal.domain.model.LegalMatter;
import br.com.gestorcobranca.legal.domain.repository.LawFirmMemberRepository;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Usuários atribuíveis nos campos do jurídico (decisão 686). Elegibilidade igual à que o serviço exige ao gravar
 * ({@code LegalMatterService.assign}, {@code DeadlineCommands}, {@code LawFirmService.addMember}): usuário ativo com
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
        int size = Math.min(page.size(), MAX_PAGE_SIZE);

        Map<UUID, UUID> firmOf = members.findByActiveTrue().stream()
                .collect(Collectors.toMap(LawFirmMember::getUserId, LawFirmMember::getLawFirmId, (a, b) -> a));
        LegalMatter matter = null;
        Predicate<UserSummary> eligible;
        switch (purpose) {
            case MATTER_RESPONSIBLE -> {
                access.requireInternal(RESOURCE);
                matter = access.visible(requireMatter(matterId));
                UUID creditorId = matter.getCreditorId();
                eligible = u -> u.canAccessCreditor(creditorId) && !firmOf.containsKey(u.id());
            }
            case DEADLINE_RESPONSIBLE -> {
                matter = access.visible(requireMatter(matterId));
                UUID creditorId = matter.getCreditorId();
                LegalAccess.ActorScope scope = access.scope();
                eligible = scope.external()
                        ? u -> u.canAccessCreditor(creditorId) && scope.lawFirmId().equals(firmOf.get(u.id()))
                        : u -> u.canAccessCreditor(creditorId);
            }
            case FIRM_MEMBER -> {
                access.requireInternal(RESOURCE);
                access.requireAllCreditors(RESOURCE);
                eligible = u -> !firmOf.containsKey(u.id());
            }
            default -> throw new IllegalStateException("campo não tratado: " + purpose);
        }

        String needle = normalize(query);
        List<AssigneeView> all = users.activeUsersWithPermission(Permission.LEGAL_MANAGE.name()).stream()
                .filter(UserSummary::active)
                .filter(eligible)
                .filter(u -> ids == null || ids.isEmpty() || ids.contains(u.id()))
                .filter(u -> needle.isEmpty() || normalize(u.name()).contains(needle))
                .sorted(Comparator.comparing((UserSummary u) -> normalize(u.name())).thenComparing(UserSummary::id))
                .map(u -> new AssigneeView(u.id(), u.name()))
                .toList();
        int from = Math.min(page.page() * size, all.size());
        List<AssigneeView> content = all.subList(from, Math.min(from + size, all.size()));

        Map<String, String> details = new LinkedHashMap<>();
        details.put("resource", RESOURCE);
        details.put("purpose", purpose.name());
        if (matter != null) {
            details.put("matterId", matter.getId().toString());
        }
        details.put("returned", String.valueOf(content.size()));
        accessLogger.log(AccessEvent.of(AccessEventType.USER_DIRECTORY_QUERIED, access.actor(), details));
        return new PageResponse<>(content, page.page(), size, all.size(), (all.size() + size - 1) / size);
    }

    private static UUID requireMatter(UUID matterId) {
        Guard.require(matterId != null, "legal_assignee.matter_required", "Informe o caso jurídico");
        return matterId;
    }

    /** Minúsculas e sem acentos, para buscar "Joao" e achar "João". */
    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
