package br.com.gestorcobranca.compliance.web;

import br.com.gestorcobranca.compliance.application.port.in.DataSubjectRequestUseCases;
import br.com.gestorcobranca.compliance.application.port.in.DataSubjectRequestUseCases.OpenRequestCommand;
import br.com.gestorcobranca.compliance.application.port.in.DataSubjectRequestUseCases.RequestFilter;
import br.com.gestorcobranca.compliance.application.port.in.DataSubjectRequestUseCases.RequestView;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

/**
 * Solicitações de titulares (LGPD art. 18). A busca por documento usa POST para que o CPF/CNPJ
 * não trafegue na URL.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/compliance/data-subject-requests")
class DataSubjectRequestController {

    private final DataSubjectRequestUseCases requests;

    DataSubjectRequestController(DataSubjectRequestUseCases requests) {
        this.requests = requests;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('DATA_SUBJECT_REQUEST_MANAGE')")
    PageResponse<RequestView> search(@RequestParam(required = false) String status,
                                     @RequestParam(required = false) String type,
                                     @RequestParam(required = false) UUID debtorId,
                                     @RequestParam(required = false) Boolean overdue,
                                     @RequestParam(required = false) Integer page,
                                     @RequestParam(required = false) Integer size) {
        return requests.search(new RequestFilter(status, type, debtorId, null, overdue), PageQuery.of(page, size));
    }

    @PostMapping("/lookup")
    @PreAuthorize("hasAuthority('DATA_SUBJECT_REQUEST_MANAGE')")
    PageResponse<RequestView> findByDocument(@Valid @RequestBody DocumentLookupRequest request,
                                             @RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size) {
        return requests.search(new RequestFilter(null, null, null, request.document(), null), PageQuery.of(page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('DATA_SUBJECT_REQUEST_MANAGE')")
    RequestView get(@PathVariable UUID id) {
        return requests.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('DATA_SUBJECT_REQUEST_MANAGE')")
    RequestView open(@Valid @RequestBody OpenRequest request) {
        return requests.open(new OpenRequestCommand(request.type(), request.channel(), request.requesterName(),
                request.document(), request.requesterContact(), request.details(), request.receivedAt()));
    }

    @PostMapping("/{id}/verify-identity")
    @PreAuthorize("hasAuthority('DATA_SUBJECT_REQUEST_MANAGE')")
    RequestView verifyIdentity(@PathVariable UUID id, @RequestBody(required = false) VerifyIdentityRequest request) {
        return requests.verifyIdentity(id, request == null ? null : request.debtorId());
    }

    @PostMapping("/{id}/start")
    @PreAuthorize("hasAuthority('DATA_SUBJECT_REQUEST_MANAGE')")
    RequestView start(@PathVariable UUID id, @RequestBody(required = false) AssignRequest request) {
        return requests.start(id, request == null ? null : request.assignee());
    }

    @PostMapping("/{id}/assign")
    @PreAuthorize("hasAuthority('DATA_SUBJECT_REQUEST_MANAGE')")
    RequestView assign(@PathVariable UUID id, @Valid @RequestBody AssignRequest request) {
        return requests.assign(id, request.assignee());
    }

    @PostMapping("/{id}/execute")
    @PreAuthorize("hasAuthority('DATA_SUBJECT_REQUEST_MANAGE')")
    RequestView execute(@PathVariable UUID id) {
        return requests.execute(id);
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasAuthority('DATA_SUBJECT_REQUEST_MANAGE')")
    RequestView complete(@PathVariable UUID id, @Valid @RequestBody ResponseRequest request) {
        return requests.complete(id, request.response());
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('DATA_SUBJECT_REQUEST_MANAGE')")
    RequestView reject(@PathVariable UUID id, @Valid @RequestBody ResponseRequest request) {
        return requests.reject(id, request.response());
    }

    @GetMapping("/{id}/result")
    @PreAuthorize("hasAuthority('DATA_SUBJECT_REQUEST_MANAGE')")
    ResponseEntity<InputStreamResource> downloadResult(@PathVariable UUID id) {
        return FileResponses.attachment(requests.openResult(id));
    }

    record OpenRequest(@NotBlank @Size(max = 40) String type, @NotBlank @Size(max = 20) String channel,
                       @NotBlank @Size(max = 200) String requesterName, @Size(max = 20) String document,
                       @Size(max = 254) String requesterContact, @Size(max = 4000) String details, Instant receivedAt) {
    }

    record VerifyIdentityRequest(UUID debtorId) {
    }

    record AssignRequest(@NotNull UUID assignee) {
    }

    record ResponseRequest(@NotBlank @Size(max = 4000) String response) {
    }

    record DocumentLookupRequest(@NotBlank @Size(max = 20) String document) {
    }
}
