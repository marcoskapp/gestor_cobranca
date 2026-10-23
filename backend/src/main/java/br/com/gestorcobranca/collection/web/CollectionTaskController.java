package br.com.gestorcobranca.collection.web;

import br.com.gestorcobranca.collection.application.port.in.TaskUseCases;
import br.com.gestorcobranca.collection.application.port.in.TaskUseCases.ScheduleTaskCommand;
import br.com.gestorcobranca.collection.application.port.in.TaskUseCases.TaskView;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Tarefas, retornos e agenda. Datas e horas no fuso de operação (America/Sao_Paulo). */
@RestController
@RequestMapping(ApiPaths.V1 + "/collection")
class CollectionTaskController {

    private final TaskUseCases tasks;

    CollectionTaskController(TaskUseCases tasks) {
        this.tasks = tasks;
    }

    @PostMapping("/cases/{caseId}/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('TASK_MANAGE')")
    TaskView schedule(@PathVariable UUID caseId, @Valid @RequestBody ScheduleRequest request) {
        return tasks.schedule(caseId, new ScheduleTaskCommand(request.type(), request.dueAt(), request.assigneeUserId(),
                request.note()));
    }

    @GetMapping("/cases/{caseId}/tasks")
    @PreAuthorize("hasAuthority('CASE_VIEW')")
    List<TaskView> byCase(@PathVariable UUID caseId) {
        return tasks.listByCase(caseId);
    }

    @GetMapping("/agenda")
    @PreAuthorize("hasAuthority('TASK_MANAGE') or hasAuthority('CASE_WORK')")
    PageResponse<TaskView> agenda(@RequestParam(required = false) UUID userId,
                                  @RequestParam(required = false) LocalDate from,
                                  @RequestParam(required = false) LocalDate to,
                                  @RequestParam(required = false) List<String> status,
                                  @RequestParam(required = false) Integer page,
                                  @RequestParam(required = false) Integer size) {
        return tasks.agenda(userId, from, to, status, PageQuery.of(page, size));
    }

    @PostMapping("/tasks/{id}/complete")
    @PreAuthorize("hasAuthority('TASK_MANAGE')")
    TaskView complete(@PathVariable UUID id, @RequestBody(required = false) CompleteRequest request) {
        return tasks.complete(id, request == null ? null : request.interactionId());
    }

    @PostMapping("/tasks/{id}/cancel")
    @PreAuthorize("hasAuthority('TASK_MANAGE')")
    TaskView cancel(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
        return tasks.cancel(id, request.reason());
    }

    @PostMapping("/tasks/{id}/reschedule")
    @PreAuthorize("hasAuthority('TASK_MANAGE')")
    TaskView reschedule(@PathVariable UUID id, @Valid @RequestBody RescheduleRequest request) {
        return tasks.reschedule(id, request.dueAt());
    }

    @PostMapping("/tasks/{id}/reassign")
    @PreAuthorize("hasAuthority('TASK_MANAGE') and hasAuthority('CASE_ASSIGN')")
    TaskView reassign(@PathVariable UUID id, @RequestBody ReassignRequest request) {
        return tasks.reassign(id, request.userId());
    }

    record ScheduleRequest(@NotBlank @Size(max = 20) String type, @NotNull LocalDateTime dueAt, UUID assigneeUserId,
                           @Size(max = 1000) String note) {
    }

    record CompleteRequest(UUID interactionId) {
    }

    record ReasonRequest(@NotBlank @Size(max = 500) String reason) {
    }

    record RescheduleRequest(@NotNull LocalDateTime dueAt) {
    }

    /** @param userId nulo = tarefa da fila do caso */
    record ReassignRequest(UUID userId) {
    }
}
