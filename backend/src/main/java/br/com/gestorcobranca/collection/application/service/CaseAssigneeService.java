package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.application.port.in.CaseAssigneeUseCases;
import br.com.gestorcobranca.collection.domain.model.CollectionCase;
import br.com.gestorcobranca.collection.domain.repository.CollectionCaseRepository;
import br.com.gestorcobranca.collection.domain.repository.DisputePoolMemberRepository;
import br.com.gestorcobranca.collection.domain.repository.DisputeRepository;
import br.com.gestorcobranca.identity.application.port.in.UserDirectory;
import br.com.gestorcobranca.identity.application.port.in.UserDirectory.UserSummary;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.logging.access.AccessEvent;
import br.com.gestorcobranca.shared.logging.access.AccessEventType;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.paging.NameSearch;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.Permission;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Usuários atribuíveis nos campos de responsável da cobrança. A elegibilidade é a mesma função que o serviço aplica ao
 * gravar — {@link CaseWorkers#canWork} ({@code CaseWorkService.assign}, {@code TaskService}),
 * {@link CaseWorkers#canReceiveRedistribution} ({@code CaseWorkService.redistribute}),
 * {@link DisputeAssignments#isEligibleForPool} ({@code DisputeConfigService.addPoolMember}) e
 * {@link DisputeAssignments#isEligibleForAll} ({@code DisputeAssignments.redistribute}) —, com as mesmas permissões e o
 * mesmo escopo de credores exigidos na gravação. Só id, nome e situação; cada consulta vai para o log de acesso
 * ({@link AccessEventType#USER_DIRECTORY_QUERIED}) com o campo, o contexto e a quantidade devolvida — nunca o texto
 * buscado.
 */
@Service
@Transactional(readOnly = true)
class CaseAssigneeService implements CaseAssigneeUseCases {

    private static final String RESOURCE = "collection_assignee";

    private final UserDirectory users;
    private final CaseAccess caseAccess;
    private final CollectionCaseRepository cases;
    private final DisputeRepository disputes;
    private final DisputePoolMemberRepository pool;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AccessLogger accessLogger;

    CaseAssigneeService(UserDirectory users, CaseAccess caseAccess, CollectionCaseRepository cases,
                        DisputeRepository disputes, DisputePoolMemberRepository pool, AccessScope accessScope,
                        CurrentActor currentActor, AccessLogger accessLogger) {
        this.users = users;
        this.caseAccess = caseAccess;
        this.cases = cases;
        this.disputes = disputes;
        this.pool = pool;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.accessLogger = accessLogger;
    }

    @Override
    public PageResponse<AssigneeView> search(AssigneeQuery query, PageQuery page) {
        Guard.require(query != null && query.purpose() != null, "collection_assignee.purpose_required",
                "Informe o campo da busca");
        Guard.require(query.ids() == null || query.ids().size() <= MAX_IDS, "collection_assignee.ids_limit",
                "Informe no máximo %d usuários por consulta".formatted(MAX_IDS));
        Map<String, String> details = new LinkedHashMap<>();
        details.put("resource", RESOURCE);
        details.put("purpose", query.purpose().name());

        Collection<UserSummary> candidates;
        Predicate<UserSummary> eligible;
        switch (query.purpose()) {
            case CASE_WORKER -> {
                requireUserPermission(Permission.CASE_ASSIGN);
                Guard.require(query.caseId() != null, "collection_assignee.case_required", "Informe o caso");
                CollectionCase c = caseAccess.visible(query.caseId());
                UUID creditorId = c.getCreditorId();
                details.put("caseId", c.getId().toString());
                candidates = users.activeUsersWithPermission(Permission.CASE_WORK.name());
                eligible = u -> CaseWorkers.canWork(u, creditorId);
            }
            case CASE_REDISTRIBUTION_SOURCE -> {
                requireUserPermission(Permission.CASE_ASSIGN);
                candidates = users.findAll(cases.findActiveAssigneeIds(caseAccess.creditorFilter()));
                eligible = u -> true;
            }
            case CASE_REDISTRIBUTION_TARGET -> {
                requireUserPermission(Permission.CASE_ASSIGN);
                Set<UUID> actorCreditors = caseAccess.creditorFilter();
                UUID from = query.fromUserId();
                candidates = users.activeUsersWithPermission(Permission.CASE_WORK.name());
                eligible = u -> CaseWorkers.canReceiveRedistribution(u, from, actorCreditors);
            }
            case DISPUTE_POOL_MEMBER -> {
                UUID creditorId = query.creditorId();
                requireDisputeManage();
                if (creditorId == null) {
                    accessScope.requireAllCreditors("dispute_pool_member");
                } else {
                    accessScope.requireCreditor(creditorId);
                    details.put("creditorId", creditorId.toString());
                }
                Set<UUID> members = DisputeConfigService.memberIds(pool.findByActiveTrue(), creditorId);
                candidates = disputeWorkers();
                eligible = u -> DisputeAssignments.isEligibleForPool(u, creditorId) && !members.contains(u.id());
            }
            case DISPUTE_REDISTRIBUTION_SOURCE -> {
                requireDisputeManage();
                candidates = users.findAll(disputes.findOpenAssigneeIds(caseAccess.creditorFilter()));
                eligible = u -> true;
            }
            case DISPUTE_REDISTRIBUTION_TARGET -> {
                requireDisputeManage();
                UUID from = query.fromUserId();
                Set<UUID> creditors = disputes.findOpenCreditorIdsByAssignee(from, caseAccess.creditorFilter());
                candidates = disputeWorkers();
                eligible = u -> !u.id().equals(from) && DisputeAssignments.isEligibleForAll(u, creditors);
            }
            default -> throw new IllegalStateException("campo não tratado: " + query.purpose());
        }

        List<UserSummary> filtered = candidates.stream().filter(eligible).toList();
        PageResponse<AssigneeView> result = NameSearch.page(filtered, UserSummary::id, UserSummary::name, query.query(),
                query.ids(), page, MAX_PAGE_SIZE, u -> new AssigneeView(u.id(), u.name(), u.active()));
        details.put("returned", String.valueOf(result.content().size()));
        accessLogger.log(AccessEvent.of(AccessEventType.USER_DIRECTORY_QUERIED, currentActor.get(), details));
        return result;
    }

    /** Usuários ativos com {@code DISPUTE_WORK} ou {@code DISPUTE_MANAGE}, sem repetição. */
    private Collection<UserSummary> disputeWorkers() {
        Map<UUID, UserSummary> byId = new LinkedHashMap<>();
        for (Permission permission : List.of(Permission.DISPUTE_WORK, Permission.DISPUTE_MANAGE)) {
            users.activeUsersWithPermission(permission.name()).forEach(u -> byId.putIfAbsent(u.id(), u));
        }
        return byId.values();
    }

    /** Mesma exigência da gravação: usuário com a permissão (atores de sistema passam, como nos serviços). */
    private void requireUserPermission(Permission permission) {
        Actor actor = currentActor.get();
        if (actor.isUser()) {
            accessScope.requirePermission(permission);
        }
    }

    private void requireDisputeManage() {
        requireUserPermission(Permission.DISPUTE_MANAGE);
    }
}
