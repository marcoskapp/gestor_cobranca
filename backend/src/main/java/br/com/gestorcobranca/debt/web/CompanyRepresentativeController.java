package br.com.gestorcobranca.debt.web;

import br.com.gestorcobranca.debt.application.port.in.CompanyRepresentativeUseCases;
import br.com.gestorcobranca.debt.application.port.in.CompanyRepresentativeUseCases.AddRepresentativeCommand;
import br.com.gestorcobranca.debt.application.port.in.CompanyRepresentativeUseCases.RepresentativeView;
import br.com.gestorcobranca.debt.application.port.in.CompanyRepresentativeUseCases.RevealedRepresentative;
import br.com.gestorcobranca.debt.application.port.in.CompanyRepresentativeUseCases.UpdateRepresentativeCommand;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Representantes da empresa devedora (B3). CPF sempre mascarado, exceto na revelação auditada. */
@RestController
@RequestMapping(ApiPaths.V1 + "/debtors/{debtorId}/representatives")
class CompanyRepresentativeController {

    private static final String ROLES = "MANAGING_PARTNER|ATTORNEY_IN_FACT|OTHER";

    private final CompanyRepresentativeUseCases representatives;

    CompanyRepresentativeController(CompanyRepresentativeUseCases representatives) {
        this.representatives = representatives;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('DEBTOR_VIEW')")
    List<RepresentativeView> list(@PathVariable UUID debtorId) {
        return representatives.list(debtorId);
    }

    @GetMapping("/{representativeId}/document")
    @PreAuthorize("hasAuthority('DEBTOR_SENSITIVE_VIEW')")
    RevealedRepresentative reveal(@PathVariable UUID debtorId, @PathVariable UUID representativeId) {
        return representatives.revealDocument(debtorId, representativeId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('DEBTOR_MANAGE')")
    RepresentativeView add(@PathVariable UUID debtorId, @Valid @RequestBody AddRepresentativeRequest request) {
        return representatives.add(debtorId, new AddRepresentativeCommand(request.document(), request.name(),
                request.role(), request.canNegotiate(), request.validUntil(), request.evidenceNote()));
    }

    @PutMapping("/{representativeId}")
    @PreAuthorize("hasAuthority('DEBTOR_MANAGE')")
    RepresentativeView update(@PathVariable UUID debtorId, @PathVariable UUID representativeId,
                              @Valid @RequestBody UpdateRepresentativeRequest request) {
        return representatives.update(debtorId, representativeId, new UpdateRepresentativeCommand(request.name(),
                request.role(), request.canNegotiate(), request.validUntil(), request.evidenceNote()));
    }

    @PostMapping("/{representativeId}/end")
    @PreAuthorize("hasAuthority('DEBTOR_MANAGE')")
    RepresentativeView end(@PathVariable UUID debtorId, @PathVariable UUID representativeId,
                           @Valid @RequestBody EndRequest request) {
        return representatives.end(debtorId, representativeId, request.reason());
    }

    record AddRepresentativeRequest(@NotBlank @Size(max = 20) String document, @NotBlank @Size(max = 200) String name,
                                    @NotBlank @Pattern(regexp = ROLES) String role, boolean canNegotiate,
                                    LocalDate validUntil, @Size(max = 200) String evidenceNote) {
    }

    record UpdateRepresentativeRequest(@NotBlank @Size(max = 200) String name,
                                       @NotBlank @Pattern(regexp = ROLES) String role, boolean canNegotiate,
                                       LocalDate validUntil, @Size(max = 200) String evidenceNote) {
    }

    record EndRequest(@NotBlank @Size(max = 300) String reason) {
    }
}
