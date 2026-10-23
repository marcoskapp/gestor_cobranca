package br.com.gestorcobranca.debt.web;

import br.com.gestorcobranca.debt.application.port.in.ObligationPartyUseCases;
import br.com.gestorcobranca.debt.application.port.in.ObligationPartyUseCases.AddPartyCommand;
import br.com.gestorcobranca.debt.application.port.in.ObligationPartyUseCases.PartyView;
import br.com.gestorcobranca.debt.application.port.in.OriginContractUseCases;
import br.com.gestorcobranca.debt.application.port.in.OriginContractUseCases.ClassifyContractCommand;
import br.com.gestorcobranca.debt.application.port.in.OriginContractUseCases.ContractView;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
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

/** Contrato de origem: natureza do título e partes da obrigação (devedor principal e coobrigados). */
@RestController
@RequestMapping(ApiPaths.V1 + "/debt/contracts/{contractId}")
class OriginContractController {

    private final OriginContractUseCases contracts;
    private final ObligationPartyUseCases parties;

    OriginContractController(OriginContractUseCases contracts, ObligationPartyUseCases parties) {
        this.contracts = contracts;
        this.parties = parties;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('DEBTOR_VIEW') or hasAuthority('CASE_VIEW')")
    ContractView get(@PathVariable UUID contractId) {
        return contracts.get(contractId);
    }

    @PutMapping("/nature")
    @PreAuthorize("hasAuthority('PRESCRIPTION_MANAGE')")
    ContractView classify(@PathVariable UUID contractId, @Valid @RequestBody NatureRequest request) {
        return contracts.classify(contractId, new ClassifyContractCommand(request.instrumentType(), request.executiveTitle()));
    }

    @GetMapping("/parties")
    @PreAuthorize("hasAuthority('DEBTOR_VIEW') or hasAuthority('CASE_VIEW')")
    List<PartyView> parties(@PathVariable UUID contractId) {
        return parties.list(contractId);
    }

    @PostMapping("/parties")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('COLLATERAL_MANAGE')")
    PartyView addParty(@PathVariable UUID contractId, @Valid @RequestBody AddPartyRequest request) {
        return parties.add(contractId, new AddPartyCommand(request.document(), request.name(), request.birthDate(),
                request.role(), request.sinceDate()));
    }

    @PostMapping("/parties/{partyId}/end")
    @PreAuthorize("hasAuthority('COLLATERAL_MANAGE')")
    PartyView endParty(@PathVariable UUID contractId, @PathVariable UUID partyId, @Valid @RequestBody EndPartyRequest request) {
        return parties.end(contractId, partyId, request.endedOn(), request.reason());
    }

    record NatureRequest(@NotBlank @Size(max = 30) String instrumentType, @NotNull Boolean executiveTitle) {
    }

    record AddPartyRequest(@NotBlank @Size(max = 20) String document,
                           @NotBlank @Size(max = 200) String name,
                           @Past LocalDate birthDate,
                           @NotBlank @Size(max = 40) String role,
                           LocalDate sinceDate) {
    }

    record EndPartyRequest(@NotNull LocalDate endedOn, @NotBlank @Size(max = 500) String reason) {
    }
}
