package br.com.gestorcobranca.collection.web;

import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.AssigneeOption;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.CreditorRequestCommand;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.CreditorRequestDraft;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.CreditorRequestView;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.CreditorResponseCommand;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.CreditorResponseView;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.DebtorNoticeCommand;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.DebtorNoticeDraft;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.DebtorNoticeView;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.DisputeDetail;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.DisputeQuery;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.DisputeView;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.EvidenceContent;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.EvidenceUpload;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.EvidenceView;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.OpenDisputeCommand;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.RedistributionResult;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.ResolveCommand;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Contestações (F21): fila, responsável, evidências, pedido ao credor, resposta do credor, resolução e resposta ao devedor. */
@RestController
@RequestMapping(ApiPaths.V1 + "/collection")
class DisputeController {

    private static final String WORK = "hasAuthority('DISPUTE_MANAGE') or hasAuthority('DISPUTE_WORK')";
    private static final String READ = "hasAuthority('DISPUTE_MANAGE') or hasAuthority('DISPUTE_WORK') or hasAuthority('CASE_VIEW')";

    private final DisputeUseCases disputes;

    DisputeController(DisputeUseCases disputes) {
        this.disputes = disputes;
    }

    @PostMapping("/cases/{caseId}/disputes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('DISPUTE_MANAGE') or hasAuthority('CASE_WORK')")
    DisputeView open(@PathVariable UUID caseId, @Valid @RequestBody OpenDisputeRequest request) {
        return disputes.open(caseId, new OpenDisputeCommand(request.titleIds(), request.type(), request.origin(),
                request.description(), request.blocksCollection(), request.responseDueDate()));
    }

    @GetMapping("/cases/{caseId}/disputes")
    @PreAuthorize("hasAuthority('CASE_VIEW')")
    List<DisputeView> byCase(@PathVariable UUID caseId) {
        return disputes.listByCase(caseId);
    }

    /**
     * Fila de contestações. {@code assignee=me|<id>}; {@code unassigned}; {@code supervision} = sem responsável ou com
     * prazo vencido (visão do supervisor); {@code dueUntil} = prazo interno ou de resposta até a data.
     */
    @GetMapping("/disputes")
    @PreAuthorize(READ)
    PageResponse<DisputeView> search(@RequestParam(required = false) UUID creditorId,
                                     @RequestParam(required = false) Set<String> status,
                                     @RequestParam(required = false) Set<String> type,
                                     @RequestParam(required = false) Set<String> origin,
                                     @RequestParam(required = false) String assignee,
                                     @RequestParam(required = false) Boolean unassigned,
                                     @RequestParam(required = false) Boolean overdue,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueUntil,
                                     @RequestParam(required = false) Boolean awaitingCreditor,
                                     @RequestParam(required = false) Boolean noticePending,
                                     @RequestParam(required = false) Boolean adjustmentPending,
                                     @RequestParam(required = false) Boolean supervision,
                                     @RequestParam(required = false) Integer page,
                                     @RequestParam(required = false) Integer size) {
        return disputes.search(new DisputeQuery(creditorId, status, type, origin, assignee, Boolean.TRUE.equals(unassigned),
                false, overdue, dueUntil, awaitingCreditor, Boolean.TRUE.equals(noticePending),
                Boolean.TRUE.equals(adjustmentPending), Boolean.TRUE.equals(supervision)), PageQuery.of(page, size));
    }

    @GetMapping("/disputes/{id}")
    @PreAuthorize(READ)
    DisputeView get(@PathVariable UUID id) {
        return disputes.get(id);
    }

    @GetMapping("/disputes/{id}/detail")
    @PreAuthorize(READ)
    DisputeDetail detail(@PathVariable UUID id) {
        return disputes.detail(id);
    }

    @PostMapping("/disputes/{id}/review")
    @PreAuthorize(WORK)
    DisputeView review(@PathVariable UUID id) {
        return disputes.startReview(id);
    }

    @PostMapping("/disputes/{id}/resolve")
    @PreAuthorize(WORK)
    DisputeView resolve(@PathVariable UUID id, @Valid @RequestBody ResolveRequest request) {
        return disputes.resolve(id, new ResolveCommand(request.outcome(), request.note(), request.adjustmentAction()));
    }

    @PostMapping("/disputes/{id}/withdraw")
    @PreAuthorize(WORK)
    DisputeView withdraw(@PathVariable UUID id, @Valid @RequestBody NoteRequest request) {
        return disputes.withdraw(id, request.note());
    }

    // ------------------------------------------------------------------ responsável

    @PostMapping("/disputes/{id}/assign")
    @PreAuthorize("hasAuthority('DISPUTE_MANAGE')")
    DisputeView assign(@PathVariable UUID id, @Valid @RequestBody AssignRequest request) {
        return disputes.assign(id, request.assigneeId(), request.reason());
    }

    @PostMapping("/disputes/{id}/take")
    @PreAuthorize(WORK)
    DisputeView take(@PathVariable UUID id) {
        return disputes.take(id);
    }

    @GetMapping("/disputes/{id}/assignee-options")
    @PreAuthorize("hasAuthority('DISPUTE_MANAGE')")
    List<AssigneeOption> assigneeOptions(@PathVariable UUID id) {
        return disputes.assigneeOptions(id);
    }

    @PostMapping("/disputes/redistributions")
    @PreAuthorize("hasAuthority('DISPUTE_MANAGE')")
    RedistributionResult redistribute(@Valid @RequestBody RedistributeRequest request) {
        return disputes.redistribute(request.fromAssigneeId(), request.toAssigneeId(), request.reason());
    }

    // ------------------------------------------------------------------ credor

    @GetMapping("/disputes/{id}/creditor-request-draft")
    @PreAuthorize(WORK)
    CreditorRequestDraft creditorRequestDraft(@PathVariable UUID id) {
        return disputes.draftCreditorRequest(id);
    }

    @PostMapping("/disputes/{id}/creditor-requests")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(WORK)
    CreditorRequestView requestCreditor(@PathVariable UUID id, @Valid @RequestBody CreditorRequestRequest request) {
        return disputes.requestCreditor(id, new CreditorRequestCommand(request.subject(), request.body(),
                request.requestedDueDate(), request.titleIds(), request.evidenceIds(),
                request.copyAssignee() == null || request.copyAssignee()));
    }

    @GetMapping("/disputes/{id}/creditor-requests")
    @PreAuthorize(READ)
    List<CreditorRequestView> creditorRequests(@PathVariable UUID id) {
        return disputes.creditorRequests(id);
    }

    /** Resposta do credor registrada pela assessoria, com os documentos recebidos (multipart: {@code response} + {@code files}). */
    @PostMapping(path = "/disputes/{id}/creditor-responses", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(WORK)
    CreditorResponseView registerCreditorResponse(@PathVariable UUID id,
                                                  @Valid @RequestPart("response") CreditorResponseRequest request,
                                                  @RequestPart(name = "files", required = false) List<MultipartFile> files) {
        List<EvidenceUpload> uploads = new ArrayList<>();
        if (files != null) {
            for (MultipartFile file : files) {
                uploads.add(upload(file));
            }
        }
        return disputes.registerCreditorResponse(id, new CreditorResponseCommand(request.position(), request.note(),
                request.requestId(), request.receivedAt()), uploads);
    }

    // ------------------------------------------------------------------ devedor

    @GetMapping("/disputes/{id}/debtor-notice-draft")
    @PreAuthorize(WORK)
    DebtorNoticeDraft debtorNoticeDraft(@PathVariable UUID id) {
        return disputes.draftDebtorNotice(id);
    }

    @PostMapping("/disputes/{id}/debtor-notices")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(WORK)
    DebtorNoticeView notifyDebtor(@PathVariable UUID id, @Valid @RequestBody DebtorNoticeRequest request) {
        return disputes.notifyDebtor(id, new DebtorNoticeCommand(request.channel(), request.contactId(), request.subject(),
                request.body()));
    }

    // ------------------------------------------------------------------ ajuste

    @PostMapping("/disputes/{id}/adjustment/dismiss")
    @PreAuthorize("hasAuthority('DISPUTE_MANAGE')")
    DisputeView dismissAdjustment(@PathVariable UUID id, @Valid @RequestBody NoteRequest request) {
        return disputes.dismissAdjustment(id, request.note());
    }

    // ------------------------------------------------------------------ evidências

    @PostMapping(path = "/disputes/{id}/evidences", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('DISPUTE_MANAGE') or hasAuthority('DISPUTE_WORK') or hasAuthority('CASE_WORK')")
    EvidenceView attach(@PathVariable UUID id, @RequestPart("file") MultipartFile file) {
        return disputes.attachEvidence(id, upload(file));
    }

    @GetMapping("/disputes/{id}/evidences")
    @PreAuthorize(READ)
    List<EvidenceView> evidences(@PathVariable UUID id) {
        return disputes.listEvidence(id);
    }

    /** Download sem cache (o conteúdo pode conter dados pessoais); registra FILE_DOWNLOADED. */
    @GetMapping("/disputes/{id}/evidences/{evidenceId}")
    @PreAuthorize("hasAuthority('DISPUTE_MANAGE') or hasAuthority('DISPUTE_WORK') or hasAuthority('CASE_WORK')")
    ResponseEntity<InputStreamResource> download(@PathVariable UUID id, @PathVariable UUID evidenceId) {
        EvidenceContent file = disputes.openEvidence(id, evidenceId);
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(file.contentType() == null ? MediaType.APPLICATION_OCTET_STREAM
                        : MediaType.parseMediaType(file.contentType()));
        if (file.size() >= 0) {
            builder.contentLength(file.size());
        }
        return builder.body(new InputStreamResource(file.content()));
    }

    private static EvidenceUpload upload(MultipartFile file) {
        try {
            return new EvidenceUpload(file.getOriginalFilename(), file.getContentType(), file.getSize(),
                    file.getInputStream());
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao ler o arquivo enviado", e);
        }
    }

    record OpenDisputeRequest(@Size(max = 200) Set<UUID> titleIds,
                              @NotBlank @Size(max = 30) String type,
                              @NotBlank @Size(max = 20) String origin,
                              @NotBlank @Size(max = 4000) String description,
                              Boolean blocksCollection,
                              LocalDate responseDueDate) {
    }

    /** @param adjustmentAction ação no título (nula = a sugerida pelo tipo; NONE = nenhuma) */
    record ResolveRequest(@NotBlank @Size(max = 20) String outcome, @NotBlank @Size(max = 4000) String note,
                          @Pattern(regexp = "NONE|CANCEL_TITLES|ADJUST_AMOUNT|SETTLE_BY_PAYMENT") String adjustmentAction) {
    }

    record NoteRequest(@NotBlank @Size(max = 4000) String note) {
    }

    /** @param assigneeId nulo = devolver sem responsável */
    record AssignRequest(UUID assigneeId, @Size(max = 500) String reason) {
    }

    /** @param toAssigneeId nulo = distribuição por menor carga entre os analistas elegíveis */
    record RedistributeRequest(@NotNull UUID fromAssigneeId, UUID toAssigneeId, @NotBlank @Size(max = 500) String reason) {
    }

    record CreditorRequestRequest(@NotBlank @Size(max = 200) String subject, @NotBlank @Size(max = 8000) String body,
                                  LocalDate requestedDueDate, @Size(max = 200) Set<UUID> titleIds,
                                  @Size(max = 20) Set<UUID> evidenceIds, Boolean copyAssignee) {
    }

    record CreditorResponseRequest(@NotBlank @Pattern(regexp = "CONFIRMS_DEBT|ACCEPTS_CLAIM|PARTIAL|INFORMATION") String position,
                                   @NotBlank @Size(max = 4000) String note, UUID requestId, Instant receivedAt) {
    }

    record DebtorNoticeRequest(@NotBlank @Pattern(regexp = "EMAIL|WHATSAPP|OTHER") String channel, UUID contactId,
                               @Size(max = 200) String subject, @NotBlank @Size(max = 8000) String body) {
    }
}
