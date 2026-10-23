package br.com.gestorcobranca.compliance.web;

import br.com.gestorcobranca.compliance.application.port.in.ContactAttemptQueries;
import br.com.gestorcobranca.compliance.application.port.in.ContactAttemptQueries.AttemptView;
import br.com.gestorcobranca.compliance.application.port.in.ContactRestrictionUseCases;
import br.com.gestorcobranca.compliance.application.port.in.ContactRestrictionUseCases.CreateRestrictionCommand;
import br.com.gestorcobranca.compliance.application.port.in.ContactRestrictionUseCases.EvidenceUpload;
import br.com.gestorcobranca.compliance.application.port.in.ContactRestrictionUseCases.RestrictionFilter;
import br.com.gestorcobranca.compliance.application.port.in.ContactRestrictionUseCases.RestrictionView;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.InputStreamResource;
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
import java.time.Instant;
import java.util.UUID;

/**
 * Restrições de contato e evidências. Valores de contato trafegam apenas no corpo das
 * requisições (nunca na URL) e voltam sempre mascarados.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/compliance")
class ContactRestrictionController {

    private final ContactRestrictionUseCases restrictions;
    private final ContactAttemptQueries attempts;

    ContactRestrictionController(ContactRestrictionUseCases restrictions, ContactAttemptQueries attempts) {
        this.restrictions = restrictions;
        this.attempts = attempts;
    }

    @GetMapping("/restrictions")
    @PreAuthorize("hasAuthority('CONTACT_RESTRICTION_MANAGE') or hasAuthority('DEBTOR_VIEW')")
    PageResponse<RestrictionView> search(@RequestParam(required = false) UUID debtorId,
                                         @RequestParam(required = false) UUID creditorId,
                                         @RequestParam(required = false) String type,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String channel,
                                         @RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size) {
        return restrictions.search(new RestrictionFilter(debtorId, creditorId, type, status, channel),
                PageQuery.of(page, size));
    }

    @GetMapping("/restrictions/{id}")
    @PreAuthorize("hasAuthority('CONTACT_RESTRICTION_MANAGE') or hasAuthority('DEBTOR_VIEW')")
    RestrictionView get(@PathVariable UUID id) {
        return restrictions.get(id);
    }

    @PostMapping("/restrictions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CONTACT_RESTRICTION_MANAGE')")
    RestrictionView create(@Valid @RequestBody CreateRestrictionRequest request) {
        return restrictions.create(new CreateRestrictionCommand(request.debtorId(), request.creditorId(), request.channel(),
                request.type(), request.contactType(), request.contactValue(), request.reason(), request.source(),
                request.startsAt(), request.endsAt()));
    }

    @PostMapping("/restrictions/{id}/revoke")
    @PreAuthorize("hasAuthority('CONTACT_RESTRICTION_MANAGE')")
    RestrictionView revoke(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
        return restrictions.revoke(id, request.reason());
    }

    @PostMapping(path = "/restrictions/{id}/evidence", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('CONTACT_RESTRICTION_MANAGE')")
    RestrictionView attachEvidence(@PathVariable UUID id, @RequestPart("file") MultipartFile file) {
        try {
            return restrictions.attachEvidence(id, new EvidenceUpload(file.getOriginalFilename(), file.getContentType(),
                    file.getSize(), file.getInputStream()));
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao ler o arquivo enviado", e);
        }
    }

    @GetMapping("/restrictions/{id}/evidence")
    @PreAuthorize("hasAuthority('CONTACT_RESTRICTION_MANAGE')")
    ResponseEntity<InputStreamResource> downloadEvidence(@PathVariable UUID id) {
        return FileResponses.attachment(restrictions.openEvidence(id));
    }

    @GetMapping("/debtors/{debtorId}/contact-attempts")
    @PreAuthorize("hasAuthority('CONTACT_RESTRICTION_MANAGE') or hasAuthority('DEBTOR_VIEW')")
    PageResponse<AttemptView> attempts(@PathVariable UUID debtorId, @RequestParam(required = false) Integer page,
                                       @RequestParam(required = false) Integer size) {
        return attempts.listByDebtor(debtorId, PageQuery.of(page, size));
    }

    /** Dossiê de evidências de contato do devedor (restrições e tentativas). */
    @GetMapping("/debtors/{debtorId}/contact-evidence")
    @PreAuthorize("hasAuthority('CONTACT_RESTRICTION_MANAGE')")
    ResponseEntity<InputStreamResource> exportEvidence(@PathVariable UUID debtorId) {
        return FileResponses.attachment(restrictions.exportDebtorEvidence(debtorId));
    }

    record CreateRestrictionRequest(
            @NotNull UUID debtorId,
            UUID creditorId,
            @Size(max = 20) String channel,
            @NotBlank @Size(max = 40) String type,
            @Size(max = 10) String contactType,
            @Size(max = 254) String contactValue,
            @NotBlank @Size(max = 1000) String reason,
            @NotBlank @Size(max = 30) String source,
            Instant startsAt,
            Instant endsAt) {
    }

    record ReasonRequest(@NotBlank @Size(max = 1000) String reason) {
    }
}
