package br.com.gestorcobranca.organization.web;

import br.com.gestorcobranca.organization.application.port.in.ContractIntegrationPricingUseCases;
import br.com.gestorcobranca.organization.application.port.in.ContractIntegrationPricingUseCases.IntegrationPricingCommand;
import br.com.gestorcobranca.organization.application.port.in.ContractIntegrationPricingUseCases.IntegrationPricingView;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases.ContractTermsCommand;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases.ContractView;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases.CreateContractCommand;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.V1 + "/contracts")
class ServiceContractController {

    private final ServiceContractUseCases contracts;
    private final ContractIntegrationPricingUseCases integrationPricing;

    ServiceContractController(ServiceContractUseCases contracts, ContractIntegrationPricingUseCases integrationPricing) {
        this.contracts = contracts;
        this.integrationPricing = integrationPricing;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('CREDITOR_VIEW')")
    List<ContractView> listByCreditor(@RequestParam UUID creditorId) {
        return contracts.listByCreditor(creditorId);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('CREDITOR_VIEW')")
    ContractView get(@PathVariable UUID id) {
        return contracts.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CONTRACT_MANAGE')")
    ContractView create(@Valid @RequestBody CreateContractRequest request) {
        return contracts.create(new CreateContractCommand(request.creditorId(), request.number(), request.terms().toCommand()));
    }

    @PutMapping("/{id}/terms")
    @PreAuthorize("hasAuthority('CONTRACT_MANAGE')")
    ContractView updateTerms(@PathVariable UUID id, @Valid @RequestBody TermsRequest request) {
        return contracts.updateTerms(id, request.toCommand());
    }

    @PostMapping("/{id}/terminate")
    @PreAuthorize("hasAuthority('CONTRACT_MANAGE')")
    ContractView terminate(@PathVariable UUID id, @Valid @RequestBody TerminateRequest request) {
        return contracts.terminate(id, request.endDate(), request.reason());
    }

    /** Versões das condições de cobrança das integrações (API pública), da mais recente para a mais antiga. */
    @GetMapping("/{id}/integration-pricing")
    @PreAuthorize("hasAuthority('CREDITOR_VIEW')")
    List<IntegrationPricingView> integrationPricing(@PathVariable UUID id) {
        return integrationPricing.history(id);
    }

    /** Nova versão das condições de cobrança das integrações; vale a partir do próximo ciclo de consumo. */
    @PostMapping("/{id}/integration-pricing")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CONTRACT_MANAGE')")
    IntegrationPricingView defineIntegrationPricing(@PathVariable UUID id,
                                                    @Valid @RequestBody IntegrationPricingRequest request) {
        return integrationPricing.define(id, request.toCommand());
    }

    record CreateContractRequest(@NotNull UUID creditorId, @NotBlank @Size(max = 60) String number,
                                 @NotNull @Valid TermsRequest terms) {
    }

    record TermsRequest(@NotNull LocalDate startDate, LocalDate endDate, @Positive Integer returnAfterDays,
                        @Min(1) @Max(28) int settlementDay, @Size(max = 2000) String notes) {

        ContractTermsCommand toCommand() {
            return new ContractTermsCommand(startDate, endDate, returnAfterDays, settlementDay, notes);
        }
    }

    record TerminateRequest(@NotNull LocalDate endDate, @NotBlank @Size(max = 500) String reason) {
    }

    record IntegrationPricingRequest(@Min(1) @Max(31) int usageClosingDay,
                                     @NotNull @DecimalMin("0") @Digits(integer = 13, fraction = 2) BigDecimal monthlyFee,
                                     @PositiveOrZero long includedRequests,
                                     @NotNull @DecimalMin("0") @Digits(integer = 13, fraction = 6) BigDecimal pricePerRequest,
                                     @PositiveOrZero long includedMbIn,
                                     @NotNull @DecimalMin("0") @Digits(integer = 13, fraction = 6) BigDecimal pricePerMbIn,
                                     @PositiveOrZero long includedMbOut,
                                     @NotNull @DecimalMin("0") @Digits(integer = 13, fraction = 6) BigDecimal pricePerMbOut,
                                     @PositiveOrZero long includedRecords,
                                     @NotNull @DecimalMin("0") @Digits(integer = 13, fraction = 6) BigDecimal pricePerRecord,
                                     @Size(max = 500) String reason) {

        IntegrationPricingCommand toCommand() {
            return new IntegrationPricingCommand(usageClosingDay, monthlyFee, includedRequests, pricePerRequest,
                    includedMbIn, pricePerMbIn, includedMbOut, pricePerMbOut, includedRecords, pricePerRecord, reason);
        }
    }
}
