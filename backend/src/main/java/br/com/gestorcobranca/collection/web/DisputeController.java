package br.com.gestorcobranca.collection.web;

import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.DisputeView;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.EvidenceContent;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.EvidenceUpload;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.EvidenceView;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.OpenDisputeCommand;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.InputStreamResource;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Contestações (F21) e evidências. */
@RestController
@RequestMapping(ApiPaths.V1 + "/collection")
class DisputeController {

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

    @GetMapping("/disputes")
    @PreAuthorize("hasAuthority('DISPUTE_MANAGE') or hasAuthority('CASE_VIEW')")
    PageResponse<DisputeView> search(@RequestParam(required = false) UUID creditorId,
                                     @RequestParam(required = false) Set<String> status,
                                     @RequestParam(required = false) Boolean overdue,
                                     @RequestParam(required = false) Integer page,
                                     @RequestParam(required = false) Integer size) {
        return disputes.search(creditorId, status, overdue, PageQuery.of(page, size));
    }

    @GetMapping("/disputes/{id}")
    @PreAuthorize("hasAuthority('DISPUTE_MANAGE') or hasAuthority('CASE_VIEW')")
    DisputeView get(@PathVariable UUID id) {
        return disputes.get(id);
    }

    @PostMapping("/disputes/{id}/review")
    @PreAuthorize("hasAuthority('DISPUTE_MANAGE')")
    DisputeView review(@PathVariable UUID id) {
        return disputes.startReview(id);
    }

    @PostMapping("/disputes/{id}/resolve")
    @PreAuthorize("hasAuthority('DISPUTE_MANAGE')")
    DisputeView resolve(@PathVariable UUID id, @Valid @RequestBody ResolveRequest request) {
        return disputes.resolve(id, request.outcome(), request.note());
    }

    @PostMapping("/disputes/{id}/withdraw")
    @PreAuthorize("hasAuthority('DISPUTE_MANAGE')")
    DisputeView withdraw(@PathVariable UUID id, @Valid @RequestBody NoteRequest request) {
        return disputes.withdraw(id, request.note());
    }

    @PostMapping(path = "/disputes/{id}/evidences", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('DISPUTE_MANAGE') or hasAuthority('CASE_WORK')")
    EvidenceView attach(@PathVariable UUID id, @RequestPart("file") MultipartFile file) {
        try {
            return disputes.attachEvidence(id, new EvidenceUpload(file.getOriginalFilename(), file.getContentType(),
                    file.getSize(), file.getInputStream()));
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao ler o arquivo enviado", e);
        }
    }

    @GetMapping("/disputes/{id}/evidences")
    @PreAuthorize("hasAuthority('DISPUTE_MANAGE') or hasAuthority('CASE_VIEW')")
    List<EvidenceView> evidences(@PathVariable UUID id) {
        return disputes.listEvidence(id);
    }

    /** Download sem cache (o conteúdo pode conter dados pessoais); registra FILE_DOWNLOADED. */
    @GetMapping("/disputes/{id}/evidences/{evidenceId}")
    @PreAuthorize("hasAuthority('DISPUTE_MANAGE') or hasAuthority('CASE_WORK')")
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

    record OpenDisputeRequest(@Size(max = 200) Set<UUID> titleIds,
                              @NotBlank @Size(max = 30) String type,
                              @NotBlank @Size(max = 20) String origin,
                              @NotBlank @Size(max = 4000) String description,
                              Boolean blocksCollection,
                              LocalDate responseDueDate) {
    }

    record ResolveRequest(@NotBlank @Size(max = 20) String outcome, @NotBlank @Size(max = 4000) String note) {
    }

    record NoteRequest(@NotBlank @Size(max = 4000) String note) {
    }
}
