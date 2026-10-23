package br.com.gestorcobranca.compliance.web;

import br.com.gestorcobranca.compliance.application.port.in.LegalBasisUseCases;
import br.com.gestorcobranca.compliance.application.port.in.LegalBasisUseCases.LegalBasisView;
import br.com.gestorcobranca.compliance.application.port.in.LegalBasisUseCases.RegisterLegalBasisCommand;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
import java.util.List;
import java.util.UUID;

/** Bases legais do tratamento por credor/carteira. */
@RestController
@RequestMapping(ApiPaths.V1 + "/compliance/legal-bases")
class LegalBasisController {

    private final LegalBasisUseCases legalBases;

    LegalBasisController(LegalBasisUseCases legalBases) {
        this.legalBases = legalBases;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('DATA_SUBJECT_REQUEST_MANAGE')")
    List<LegalBasisView> listByCreditor(@RequestParam UUID creditorId) {
        return legalBases.listByCreditor(creditorId);
    }

    @GetMapping("/effective")
    @PreAuthorize("hasAuthority('DATA_SUBJECT_REQUEST_MANAGE')")
    LegalBasisView effective(@RequestParam UUID creditorId, @RequestParam(required = false) UUID portfolioId,
                             @RequestParam(required = false) LocalDate date) {
        return legalBases.findEffective(creditorId, portfolioId, date)
                .orElseThrow(() -> new ResourceNotFoundException("legal_basis_record", creditorId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('DATA_SUBJECT_REQUEST_MANAGE')")
    LegalBasisView register(@Valid @RequestBody RegisterRequest request) {
        return legalBases.register(new RegisterLegalBasisCommand(request.creditorId(), request.portfolioId(),
                request.legalBasis(), request.processingRole(), request.contractReference(), request.validFrom(),
                request.validUntil(), request.legalResponsible(), request.retentionMonths(), request.notes()));
    }

    @PostMapping("/{id}/end")
    @PreAuthorize("hasAuthority('DATA_SUBJECT_REQUEST_MANAGE')")
    LegalBasisView end(@PathVariable UUID id, @Valid @RequestBody EndRequest request) {
        return legalBases.end(id, request.lastDay(), request.reason());
    }

    record RegisterRequest(@NotNull UUID creditorId, UUID portfolioId, @NotBlank @Size(max = 40) String legalBasis,
                           @Size(max = 20) String processingRole, @NotBlank @Size(max = 120) String contractReference,
                           @NotNull LocalDate validFrom, LocalDate validUntil,
                           @NotBlank @Size(max = 150) String legalResponsible,
                           @Min(1) @Max(1200) int retentionMonths, @Size(max = 1000) String notes) {
    }

    record EndRequest(@NotNull LocalDate lastDay, @NotBlank @Size(max = 500) String reason) {
    }
}
