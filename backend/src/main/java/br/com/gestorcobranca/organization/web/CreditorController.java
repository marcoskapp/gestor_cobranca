package br.com.gestorcobranca.organization.web;

import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases.CreditorCommand;
import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases.CreditorView;
import br.com.gestorcobranca.shared.web.ApiPaths;
import br.com.gestorcobranca.shared.web.PageQuery;
import br.com.gestorcobranca.shared.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.V1 + "/creditors")
class CreditorController {

    private final CreditorUseCases creditors;

    CreditorController(CreditorUseCases creditors) {
        this.creditors = creditors;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('CREDITOR_VIEW')")
    PageResponse<CreditorView> search(@RequestParam(required = false) String q,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) Integer page,
                                      @RequestParam(required = false) Integer size) {
        return creditors.search(q, status, PageQuery.of(page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('CREDITOR_VIEW')")
    CreditorView get(@PathVariable UUID id) {
        return creditors.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CREDITOR_MANAGE')")
    CreditorView create(@Valid @RequestBody CreditorRequest request) {
        return creditors.create(request.toCommand());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('CREDITOR_MANAGE')")
    CreditorView update(@PathVariable UUID id, @Valid @RequestBody CreditorRequest request) {
        return creditors.update(id, request.toCommand());
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('CREDITOR_MANAGE')")
    CreditorView activate(@PathVariable UUID id) {
        return creditors.changeStatus(id, true);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('CREDITOR_MANAGE')")
    CreditorView deactivate(@PathVariable UUID id) {
        return creditors.changeStatus(id, false);
    }

    record CreditorRequest(
            @NotBlank @Size(max = 200) String legalName,
            @Size(max = 200) String tradeName,
            @NotBlank @Size(max = 18) String document,
            @Size(max = 60) String externalCode,
            @Email @Size(max = 200) String contactEmail) {

        CreditorCommand toCommand() {
            return new CreditorCommand(legalName, tradeName, document, externalCode, contactEmail);
        }
    }
}
