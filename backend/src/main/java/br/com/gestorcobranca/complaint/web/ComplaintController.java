package br.com.gestorcobranca.complaint.web;

import br.com.gestorcobranca.complaint.application.port.in.ComplaintUseCases;
import br.com.gestorcobranca.complaint.application.port.in.ComplaintUseCases.AssigneeOption;
import br.com.gestorcobranca.complaint.application.port.in.ComplaintUseCases.ComplaintDetail;
import br.com.gestorcobranca.complaint.application.port.in.ComplaintUseCases.ComplaintQuery;
import br.com.gestorcobranca.complaint.application.port.in.ComplaintUseCases.ComplaintView;
import br.com.gestorcobranca.complaint.application.port.in.ComplaintUseCases.CreditorIndicator;
import br.com.gestorcobranca.complaint.application.port.in.ComplaintUseCases.DeadlineRuleView;
import br.com.gestorcobranca.complaint.application.port.in.ComplaintUseCases.EvidenceContent;
import br.com.gestorcobranca.complaint.application.port.in.ComplaintUseCases.EvidenceUpload;
import br.com.gestorcobranca.complaint.application.port.in.ComplaintUseCases.EvidenceView;
import br.com.gestorcobranca.complaint.application.port.in.ComplaintUseCases.ReclassifyCommand;
import br.com.gestorcobranca.complaint.application.port.in.ComplaintUseCases.RegisterComplaintCommand;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.InputStreamResource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Reclamações de canais externos (docs/tecnico/desenho/conformidade.md §3). */
@RestController
@RequestMapping(ApiPaths.V1 + "/complaints")
class ComplaintController {

    private static final String READ = "hasAuthority('COMPLAINT_REGISTER') or hasAuthority('COMPLAINT_MANAGE')";
    private static final String MANAGE = "hasAuthority('COMPLAINT_MANAGE')";

    private final ComplaintUseCases complaints;

    ComplaintController(ComplaintUseCases complaints) {
        this.complaints = complaints;
    }

    @GetMapping
    @PreAuthorize(READ)
    PageResponse<ComplaintView> search(@RequestParam(required = false) UUID creditorId,
                                       @RequestParam(required = false) Set<String> status,
                                       @RequestParam(required = false) Set<String> channel,
                                       @RequestParam(required = false) UUID debtorId,
                                       @RequestParam(required = false) UUID caseId,
                                       @RequestParam(required = false) String assignee,
                                       @RequestParam(required = false, defaultValue = "false") boolean unassigned,
                                       @RequestParam(required = false) String deadline,
                                       @RequestParam(required = false) @Size(max = 60) String protocol,
                                       @RequestParam(required = false) Integer page,
                                       @RequestParam(required = false) Integer size) {
        return complaints.search(new ComplaintQuery(creditorId, status, channel, debtorId, caseId, assignee, unassigned,
                deadline, protocol), PageQuery.of(page, size));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(READ)
    ComplaintView register(@Valid @RequestBody RegisterRequest r) {
        return complaints.register(new RegisterComplaintCommand(r.creditorId(), r.debtorId(), r.caseId(), r.channel(),
                r.externalProtocol(), r.subject(), r.summary(), r.receivedAt(), r.responseDueDate(), r.assigneeId()));
    }

    @GetMapping("/{id}")
    @PreAuthorize(READ)
    ComplaintDetail detail(@PathVariable UUID id) {
        return complaints.detail(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize(READ)
    ComplaintView reclassify(@PathVariable UUID id, @Valid @RequestBody ReclassifyRequest r) {
        return complaints.reclassify(id, new ReclassifyCommand(r.debtorId(), r.caseId(), r.externalProtocol(),
                r.subject()));
    }

    @PutMapping("/{id}/due-date")
    @PreAuthorize(MANAGE)
    ComplaintView changeDueDate(@PathVariable UUID id, @Valid @RequestBody DueDateRequest r) {
        return complaints.changeDueDate(id, r.dueDate(), r.reason());
    }

    @PostMapping("/{id}/assign")
    @PreAuthorize(MANAGE)
    ComplaintView assign(@PathVariable UUID id, @RequestBody AssignRequest r) {
        return complaints.assign(id, r == null ? null : r.assigneeId());
    }

    @GetMapping("/{id}/assignee-options")
    @PreAuthorize(MANAGE)
    List<AssigneeOption> assigneeOptions(@PathVariable UUID id) {
        return complaints.assigneeOptions(id);
    }

    @PostMapping("/{id}/start")
    @PreAuthorize(MANAGE)
    ComplaintView start(@PathVariable UUID id) {
        return complaints.start(id);
    }

    @PostMapping("/{id}/answer")
    @PreAuthorize(MANAGE)
    ComplaintView answer(@PathVariable UUID id, @Valid @RequestBody AnswerRequest r) {
        return complaints.answer(id, r.response(), r.answeredAt());
    }

    @PostMapping("/{id}/close")
    @PreAuthorize(MANAGE)
    ComplaintView close(@PathVariable UUID id, @Valid @RequestBody CloseRequest r) {
        return complaints.close(id, r.outcome());
    }

    @GetMapping("/{id}/evidences")
    @PreAuthorize(READ)
    List<EvidenceView> evidences(@PathVariable UUID id) {
        return complaints.evidences(id);
    }

    @PostMapping(path = "/{id}/evidences", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(READ)
    EvidenceView attach(@PathVariable UUID id, @RequestPart("file") MultipartFile file) {
        try {
            return complaints.attachEvidence(id, new EvidenceUpload(file.getOriginalFilename(), file.getContentType(),
                    file.getSize(), file.getInputStream()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @GetMapping("/{id}/evidences/{evidenceId}")
    @PreAuthorize(READ)
    ResponseEntity<InputStreamResource> download(@PathVariable UUID id, @PathVariable UUID evidenceId) {
        EvidenceContent content = complaints.openEvidence(id, evidenceId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(content.fileName(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.parseMediaType(content.contentType()))
                .body(new InputStreamResource(content.content()));
    }

    @GetMapping("/deadline-rules")
    @PreAuthorize(READ)
    List<DeadlineRuleView> rules() {
        return complaints.deadlineRules();
    }

    @PutMapping("/deadline-rules/{channel}")
    @PreAuthorize(MANAGE)
    DeadlineRuleView updateRule(@PathVariable String channel, @Valid @RequestBody RuleRequest r) {
        return complaints.updateDeadlineRule(channel, r.responseDays(), r.warningDays());
    }

    @GetMapping("/indicators/by-creditor")
    @PreAuthorize(MANAGE)
    List<CreditorIndicator> indicator(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return complaints.indicatorByCreditor(from, to);
    }

    record RegisterRequest(@NotNull UUID creditorId, UUID debtorId, UUID caseId, @NotBlank @Size(max = 20) String channel,
                           @Size(max = 60) String externalProtocol, @NotBlank @Size(max = 25) String subject,
                           @NotBlank @Size(max = 4000) String summary, Instant receivedAt, LocalDate responseDueDate,
                           UUID assigneeId) {
    }

    record ReclassifyRequest(UUID debtorId, UUID caseId, @Size(max = 60) String externalProtocol,
                             @NotBlank @Size(max = 25) String subject) {
    }

    record DueDateRequest(@NotNull LocalDate dueDate, @NotBlank @Size(max = 300) String reason) {
    }

    record AssignRequest(UUID assigneeId) {
    }

    record AnswerRequest(@NotBlank @Size(max = 8000) String response, Instant answeredAt) {
    }

    record CloseRequest(@NotBlank @Size(max = 20) String outcome) {
    }

    record RuleRequest(@Min(1) @Max(120) int responseDays, @Min(0) @Max(119) int warningDays) {
    }
}
