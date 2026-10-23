package br.com.gestorcobranca.legal.web;

import br.com.gestorcobranca.legal.application.port.in.LawFirmUseCases;
import br.com.gestorcobranca.legal.application.port.in.LawFirmUseCases.LawFirmView;
import br.com.gestorcobranca.legal.application.port.in.LawFirmUseCases.MemberView;
import br.com.gestorcobranca.legal.application.port.in.LegalDeadlineUseCases;
import br.com.gestorcobranca.legal.application.port.in.LegalDeadlineUseCases.AgendaFilter;
import br.com.gestorcobranca.legal.application.port.in.LegalDeadlineUseCases.DeadlineView;
import br.com.gestorcobranca.legal.application.port.in.LegalDeadlineUseCases.DueDatePreview;
import br.com.gestorcobranca.legal.application.port.in.LegalDeadlineUseCases.DueDateQuery;
import br.com.gestorcobranca.legal.application.port.in.LegalDeadlineUseCases.SuspensionView;
import br.com.gestorcobranca.legal.application.port.in.LegalDocumentUseCases;
import br.com.gestorcobranca.legal.application.port.in.LegalDocumentUseCases.DocumentView;
import br.com.gestorcobranca.legal.application.port.in.LegalDocumentUseCases.FileDownload;
import br.com.gestorcobranca.legal.application.port.in.LegalDocumentUseCases.FileUpload;
import br.com.gestorcobranca.legal.application.port.in.LegalExpenseUseCases;
import br.com.gestorcobranca.legal.application.port.in.LegalExpenseUseCases.ExpenseView;
import br.com.gestorcobranca.legal.web.LegalRequests.CompleteRequest;
import br.com.gestorcobranca.legal.web.LegalRequests.DeadlineRequest;
import br.com.gestorcobranca.legal.web.LegalRequests.DocumentRequest;
import br.com.gestorcobranca.legal.web.LegalRequests.ExpenseRequest;
import br.com.gestorcobranca.legal.web.LegalRequests.LawFirmRequest;
import br.com.gestorcobranca.legal.web.LegalRequests.MemberRequest;
import br.com.gestorcobranca.legal.web.LegalRequests.ReasonRequest;
import br.com.gestorcobranca.legal.web.LegalRequests.ReimburseRequest;
import br.com.gestorcobranca.legal.web.LegalRequests.SuspensionRequest;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Prazos e suspensões forenses, custas e despesas, escritórios parceiros e documentos. */
@RestController
@RequestMapping(ApiPaths.V1 + "/legal")
class LegalOperationsController {

    private final LegalDeadlineUseCases deadlines;
    private final LegalExpenseUseCases expenses;
    private final LawFirmUseCases firms;
    private final LegalDocumentUseCases documents;
    private final CurrentActor currentActor;

    LegalOperationsController(LegalDeadlineUseCases deadlines, LegalExpenseUseCases expenses, LawFirmUseCases firms,
                              LegalDocumentUseCases documents, CurrentActor currentActor) {
        this.deadlines = deadlines;
        this.expenses = expenses;
        this.firms = firms;
        this.documents = documents;
        this.currentActor = currentActor;
    }

    // ---------------------------------------------------------------- prazos

    @GetMapping("/matters/{id}/deadlines")
    @PreAuthorize("hasAuthority('LEGAL_VIEW')")
    List<DeadlineView> deadlines(@PathVariable UUID id) {
        return deadlines.listByMatter(id);
    }

    @PostMapping("/matters/{id}/deadlines")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('LEGAL_MANAGE')")
    DeadlineView createDeadline(@PathVariable UUID id, @Valid @RequestBody DeadlineRequest request) {
        return deadlines.create(id, request.toCommand());
    }

    /** Agenda de prazos; {@code mine=true} restringe aos prazos do usuário. */
    @GetMapping("/deadlines")
    @PreAuthorize("hasAuthority('LEGAL_VIEW')")
    PageResponse<DeadlineView> agenda(@RequestParam(required = false) UUID matterId,
                                      @RequestParam(required = false) Set<String> status,
                                      @RequestParam(required = false) LocalDate dueFrom,
                                      @RequestParam(required = false) LocalDate dueTo,
                                      @RequestParam(required = false) UUID responsibleUserId,
                                      @RequestParam(defaultValue = "false") boolean mine,
                                      @RequestParam(required = false) Integer page,
                                      @RequestParam(required = false) Integer size) {
        UUID responsible = mine ? currentActor.actorId() : responsibleUserId;
        return deadlines.agenda(new AgendaFilter(matterId, status, dueFrom, dueTo, responsible), PageQuery.of(page, size));
    }

    @GetMapping("/deadlines/due-date")
    @PreAuthorize("hasAuthority('LEGAL_VIEW')")
    DueDatePreview dueDate(@RequestParam(required = false) LocalDate startDate,
                           @RequestParam(required = false) String startEvent,
                           @RequestParam(required = false) @Min(1) @Max(365) Integer termDays,
                           @RequestParam(required = false) String countingMode,
                           @RequestParam(required = false) LocalDate fixedDate,
                           @RequestParam(required = false) String courtCode) {
        return deadlines.preview(new DueDateQuery(startDate, startEvent, termDays, countingMode, fixedDate, courtCode));
    }

    @PostMapping("/deadlines/{deadlineId}/complete")
    @PreAuthorize("hasAuthority('LEGAL_MANAGE')")
    DeadlineView complete(@PathVariable UUID deadlineId, @Valid @RequestBody CompleteRequest request) {
        return deadlines.complete(deadlineId, request.completedOn(), request.note());
    }

    @PostMapping("/deadlines/{deadlineId}/cancel")
    @PreAuthorize("hasAuthority('LEGAL_MANAGE')")
    DeadlineView cancelDeadline(@PathVariable UUID deadlineId, @Valid @RequestBody ReasonRequest request) {
        return deadlines.cancel(deadlineId, request.reason());
    }

    @GetMapping("/court-suspensions")
    @PreAuthorize("hasAuthority('LEGAL_VIEW')")
    List<SuspensionView> suspensions() {
        return deadlines.suspensions();
    }

    @PostMapping("/court-suspensions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('LEGAL_MANAGE')")
    SuspensionView addSuspension(@Valid @RequestBody SuspensionRequest request) {
        return deadlines.addSuspension(request.toCommand());
    }

    @DeleteMapping("/court-suspensions/{suspensionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('LEGAL_MANAGE')")
    void removeSuspension(@PathVariable UUID suspensionId, @RequestParam String reason) {
        deadlines.removeSuspension(suspensionId, reason);
    }

    // ---------------------------------------------------------------- custas e despesas

    @GetMapping("/matters/{id}/expenses")
    @PreAuthorize("hasAuthority('LEGAL_VIEW')")
    List<ExpenseView> expenses(@PathVariable UUID id) {
        return expenses.list(id);
    }

    @PostMapping("/matters/{id}/expenses")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('LEGAL_EXPENSE_MANAGE')")
    ExpenseView recordExpense(@PathVariable UUID id, @Valid @RequestBody ExpenseRequest request) {
        return expenses.record(id, request.toCommand());
    }

    @PostMapping("/expenses/{expenseId}/cancel")
    @PreAuthorize("hasAuthority('LEGAL_EXPENSE_MANAGE')")
    ExpenseView cancelExpense(@PathVariable UUID expenseId, @Valid @RequestBody ReasonRequest request) {
        return expenses.cancel(expenseId, request.reason());
    }

    @PostMapping("/expenses/{expenseId}/reimburse")
    @PreAuthorize("hasAuthority('LEGAL_EXPENSE_MANAGE')")
    ExpenseView reimburse(@PathVariable UUID expenseId, @Valid @RequestBody ReimburseRequest request) {
        return expenses.reimburse(expenseId, request.reimbursedOn(), request.note());
    }

    // ---------------------------------------------------------------- escritórios parceiros

    @GetMapping("/law-firms")
    @PreAuthorize("hasAuthority('LEGAL_VIEW')")
    List<LawFirmView> firms() {
        return firms.list();
    }

    @GetMapping("/law-firms/{firmId}")
    @PreAuthorize("hasAuthority('LEGAL_VIEW')")
    LawFirmView firm(@PathVariable UUID firmId) {
        return firms.get(firmId);
    }

    @PostMapping("/law-firms")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('LEGAL_MANAGE')")
    LawFirmView createFirm(@Valid @RequestBody LawFirmRequest request) {
        return firms.create(request.toCommand());
    }

    @PutMapping("/law-firms/{firmId}")
    @PreAuthorize("hasAuthority('LEGAL_MANAGE')")
    LawFirmView updateFirm(@PathVariable UUID firmId, @Valid @RequestBody LawFirmRequest request) {
        return firms.update(firmId, request.toCommand());
    }

    @PostMapping("/law-firms/{firmId}/activate")
    @PreAuthorize("hasAuthority('LEGAL_MANAGE')")
    LawFirmView activateFirm(@PathVariable UUID firmId) {
        return firms.setActive(firmId, true);
    }

    @PostMapping("/law-firms/{firmId}/deactivate")
    @PreAuthorize("hasAuthority('LEGAL_MANAGE')")
    LawFirmView deactivateFirm(@PathVariable UUID firmId) {
        return firms.setActive(firmId, false);
    }

    @GetMapping("/law-firms/{firmId}/members")
    @PreAuthorize("hasAuthority('LEGAL_VIEW')")
    List<MemberView> members(@PathVariable UUID firmId) {
        return firms.members(firmId);
    }

    @PostMapping("/law-firms/{firmId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('LEGAL_MANAGE')")
    MemberView addMember(@PathVariable UUID firmId, @Valid @RequestBody MemberRequest request) {
        return firms.addMember(firmId, request.userId());
    }

    @PostMapping("/law-firms/{firmId}/members/{userId}/remove")
    @PreAuthorize("hasAuthority('LEGAL_MANAGE')")
    MemberView removeMember(@PathVariable UUID firmId, @PathVariable UUID userId) {
        return firms.removeMember(firmId, userId);
    }

    // ---------------------------------------------------------------- documentos

    @GetMapping("/matters/{id}/documents")
    @PreAuthorize("hasAuthority('LEGAL_VIEW')")
    List<DocumentView> documents(@PathVariable UUID id) {
        return documents.list(id);
    }

    /** Partes {@code document} (JSON) e {@code file} (PDF, PNG ou JPEG). */
    @PostMapping(path = "/matters/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('LEGAL_MANAGE') or hasAuthority('LEGAL_EXPENSE_MANAGE')")
    DocumentView upload(@PathVariable UUID id, @Valid @RequestPart("document") DocumentRequest request,
                        @RequestPart("file") MultipartFile file) {
        try {
            return documents.upload(id, request.toCommand(), new FileUpload(file.getOriginalFilename(),
                    file.getContentType(), file.getSize(), file.getInputStream()));
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao ler o arquivo enviado", e);
        }
    }

    @GetMapping("/documents/{documentId}/content")
    @PreAuthorize("hasAuthority('LEGAL_VIEW')")
    ResponseEntity<InputStreamResource> download(@PathVariable UUID documentId) {
        FileDownload file = documents.download(documentId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.size())
                .body(new InputStreamResource(file.content()));
    }
}
