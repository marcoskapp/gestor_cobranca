package br.com.gestorcobranca.collection.web;

import br.com.gestorcobranca.collection.application.port.in.CaseAssigneeUseCases;
import br.com.gestorcobranca.collection.application.port.in.CaseAssigneeUseCases.AssigneeQuery;
import br.com.gestorcobranca.collection.application.port.in.CaseAssigneeUseCases.AssigneeView;
import br.com.gestorcobranca.collection.application.port.in.CaseAssigneeUseCases.Purpose;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.web.ApiPaths;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

/** Usuários atribuíveis nos campos de responsável da cobrança (casos, tarefas, redistribuição e contestações). */
@RestController
@RequestMapping(ApiPaths.V1 + "/collection")
class CollectionAssigneeController {

    private final CaseAssigneeUseCases assignees;

    CollectionAssigneeController(CaseAssigneeUseCases assignees) {
        this.assignees = assignees;
    }

    /**
     * Busca restrita: só id, nome e situação de quem pode ser gravado no campo ({@code purpose}), sem exigir
     * {@code USER_VIEW}; paginada (até {@value CaseAssigneeUseCases#MAX_PAGE_SIZE} por página). A permissão de cada
     * campo ({@code CASE_ASSIGN} ou {@code DISPUTE_MANAGE}) é conferida no serviço.
     *
     * @param ids ids separados por vírgula, para exibir o nome do que já está escolhido
     */
    @GetMapping("/assignable-users")
    @PreAuthorize("hasAuthority('CASE_ASSIGN') or hasAuthority('DISPUTE_MANAGE')")
    PageResponse<AssigneeView> assignableUsers(@RequestParam Purpose purpose,
                                               @RequestParam(required = false) UUID caseId,
                                               @RequestParam(required = false) UUID creditorId,
                                               @RequestParam(required = false) UUID fromUserId,
                                               @RequestParam(required = false) String q,
                                               @RequestParam(required = false) Set<UUID> ids,
                                               @RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer size) {
        return assignees.search(new AssigneeQuery(purpose, caseId, creditorId, fromUserId, q, ids), PageQuery.of(page, size));
    }
}
