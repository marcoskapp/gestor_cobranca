package br.com.gestorcobranca.collection.web;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseFilter;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseSheet;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.collection.application.port.in.CaseWorkUseCases;
import br.com.gestorcobranca.collection.application.port.in.CaseWorkUseCases.AssignCommand;
import br.com.gestorcobranca.collection.application.port.in.CaseWorkUseCases.AssignmentView;
import br.com.gestorcobranca.collection.application.port.in.CaseWorkUseCases.DistributionResult;
import br.com.gestorcobranca.collection.application.port.in.CaseWorkUseCases.RedistributeCommand;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Casos de cobrança: consulta, ficha, ciclo de vida, trabalho e distribuição. */
@RestController
@RequestMapping(ApiPaths.V1 + "/collection")
class CollectionCaseController {

    private final CaseUseCases cases;
    private final CaseWorkUseCases work;
    private final CurrentActor currentActor;

    CollectionCaseController(CaseUseCases cases, CaseWorkUseCases work, CurrentActor currentActor) {
        this.cases = cases;
        this.work = work;
        this.currentActor = currentActor;
    }

    /** @param assignee id do responsável ou {@code me} */
    @GetMapping("/cases")
    @PreAuthorize("hasAuthority('CASE_VIEW')")
    PageResponse<CaseView> search(@RequestParam(required = false) UUID creditorId,
                                  @RequestParam(required = false) UUID portfolioId,
                                  @RequestParam(required = false) UUID debtorId,
                                  @RequestParam(required = false) Set<String> status,
                                  @RequestParam(required = false) UUID queueId,
                                  @RequestParam(required = false) String assignee,
                                  @RequestParam(required = false) String ownerType,
                                  @RequestParam(required = false) Boolean eligible,
                                  @RequestParam(required = false) Integer page,
                                  @RequestParam(required = false) Integer size) {
        UUID assigneeId = assignee == null || assignee.isBlank() ? null
                : "me".equalsIgnoreCase(assignee) ? currentActor.actorId() : parseId(assignee);
        return cases.search(new CaseFilter(creditorId, portfolioId, debtorId, status, queueId, assigneeId, ownerType,
                eligible), PageQuery.of(page, size));
    }

    @GetMapping("/cases/{id}")
    @PreAuthorize("hasAuthority('CASE_VIEW')")
    CaseView get(@PathVariable UUID id) {
        return cases.get(id);
    }

    @GetMapping("/cases/{id}/sheet")
    @PreAuthorize("hasAuthority('CASE_VIEW')")
    CaseSheet sheet(@PathVariable UUID id) {
        return cases.sheet(id);
    }

    @GetMapping("/debtors/{debtorId}/cases")
    @PreAuthorize("hasAuthority('CASE_VIEW')")
    List<CaseView> byDebtor(@PathVariable UUID debtorId) {
        return cases.listByDebtor(debtorId);
    }

    @PostMapping("/cases/{id}/suspend")
    @PreAuthorize("hasAuthority('CASE_MANAGE')")
    CaseView suspend(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
        return cases.suspend(id, request.reason());
    }

    @PostMapping("/cases/{id}/resume")
    @PreAuthorize("hasAuthority('CASE_MANAGE')")
    CaseView resume(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
        return cases.resume(id, request.reason());
    }

    @PostMapping("/cases/{id}/legal")
    @PreAuthorize("hasAuthority('CASE_MANAGE')")
    CaseView sendToLegal(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
        return cases.sendToLegal(id, request.reason());
    }

    @PostMapping("/cases/{id}/legal-return")
    @PreAuthorize("hasAuthority('CASE_MANAGE')")
    CaseView returnFromLegal(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
        return cases.returnFromLegal(id, request.reason());
    }

    @PostMapping("/cases/{id}/close")
    @PreAuthorize("hasAuthority('CASE_MANAGE')")
    CaseView close(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
        return cases.close(id, request.reason());
    }

    @PostMapping("/cases/{id}/refresh")
    @PreAuthorize("hasAuthority('CASE_WORK') or hasAuthority('CASE_MANAGE')")
    CaseView refresh(@PathVariable UUID id) {
        return cases.refresh(id);
    }

    @GetMapping("/cases/{id}/assignments")
    @PreAuthorize("hasAuthority('CASE_VIEW')")
    PageResponse<AssignmentView> assignments(@PathVariable UUID id, @RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size) {
        return work.assignmentHistory(id, PageQuery.of(page, size));
    }

    @PostMapping("/cases/{id}/assign")
    @PreAuthorize("hasAuthority('CASE_ASSIGN')")
    CaseView assign(@PathVariable UUID id, @Valid @RequestBody AssignRequest request) {
        return work.assign(id, new AssignCommand(request.userId(), request.teamId(), request.reason()));
    }

    @PostMapping("/cases/{id}/release")
    @PreAuthorize("hasAuthority('CASE_WORK')")
    CaseView release(@PathVariable UUID id, @Valid @RequestBody(required = false) OptionalReasonRequest request) {
        return work.release(id, request == null ? null : request.reason());
    }

    @PostMapping("/cases/{id}/take-over")
    @PreAuthorize("hasAuthority('CASE_WORK')")
    CaseView takeOver(@PathVariable UUID id, @Valid @RequestBody(required = false) OptionalReasonRequest request) {
        return work.takeOver(id, request == null ? null : request.reason());
    }

    @PostMapping("/cases/{id}/assistant")
    @PreAuthorize("hasAuthority('CASE_WORK')")
    CaseView handToAssistant(@PathVariable UUID id, @Valid @RequestBody(required = false) OptionalReasonRequest request) {
        return work.handToAssistant(id, request == null ? null : request.reason());
    }

    /** 200 com o caso entregue, ou 204 quando não há caso disponível. */
    @PostMapping("/next-case")
    @PreAuthorize("hasAuthority('CASE_WORK')")
    ResponseEntity<CaseView> nextCase(@RequestParam(required = false) UUID queueId) {
        return work.nextCase(queueId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/my-cases")
    @PreAuthorize("hasAuthority('CASE_WORK')")
    PageResponse<CaseView> myCases(@RequestParam(required = false) Integer page,
                                   @RequestParam(required = false) Integer size) {
        return work.myCases(PageQuery.of(page, size));
    }

    @PostMapping("/queues/{queueId}/distribute")
    @PreAuthorize("hasAuthority('CASE_ASSIGN')")
    DistributionResult distribute(@PathVariable UUID queueId, @Valid @RequestBody DistributeRequest request) {
        return work.distribute(queueId, request.limit());
    }

    @PostMapping("/redistributions")
    @PreAuthorize("hasAuthority('CASE_ASSIGN')")
    DistributionResult redistribute(@Valid @RequestBody RedistributeRequest request) {
        return work.redistribute(new RedistributeCommand(request.fromUserId(), request.toUserIds(), request.reason()));
    }

    private static UUID parseId(String value) {
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException("case.assignee_invalid", "Responsável inválido: use um id ou 'me'");
        }
    }

    record ReasonRequest(@NotBlank @Size(max = 500) String reason) {
    }

    record OptionalReasonRequest(@Size(max = 500) String reason) {
    }

    record AssignRequest(UUID userId, UUID teamId, @Size(max = 500) String reason) {
    }

    record DistributeRequest(@NotNull @Min(1) @Max(1000) Integer limit) {
    }

    record RedistributeRequest(@NotNull UUID fromUserId, @Size(max = 100) Set<UUID> toUserIds,
                               @Size(max = 500) String reason) {
    }
}
