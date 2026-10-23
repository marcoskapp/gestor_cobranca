package br.com.gestorcobranca.debt.web;

import br.com.gestorcobranca.debt.application.port.in.CollateralUseCases;
import br.com.gestorcobranca.debt.application.port.in.CollateralUseCases.AppraisalView;
import br.com.gestorcobranca.debt.application.port.in.CollateralUseCases.AppraiseCommand;
import br.com.gestorcobranca.debt.application.port.in.CollateralUseCases.AssetCommand;
import br.com.gestorcobranca.debt.application.port.in.CollateralUseCases.CollateralCommand;
import br.com.gestorcobranca.debt.application.port.in.CollateralUseCases.CollateralView;
import br.com.gestorcobranca.debt.application.port.in.CollateralUseCases.RegistrationCommand;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Garantias dos contratos de origem: cadastro, identificação do bem, gravame, avaliações e situação. */
@RestController
@RequestMapping(ApiPaths.V1 + "/debt")
class CollateralController {

    private final CollateralUseCases collaterals;

    CollateralController(CollateralUseCases collaterals) {
        this.collaterals = collaterals;
    }

    @GetMapping("/contracts/{contractId}/collateral")
    @PreAuthorize("hasAuthority('DEBTOR_VIEW') or hasAuthority('CASE_VIEW')")
    List<CollateralView> listByContract(@PathVariable UUID contractId) {
        return collaterals.listByContract(contractId);
    }

    @PostMapping("/contracts/{contractId}/collateral")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('COLLATERAL_MANAGE')")
    CollateralView register(@PathVariable UUID contractId, @Valid @RequestBody CollateralRequest request) {
        return collaterals.register(contractId, request.toCommand());
    }

    @GetMapping("/collateral/{id}")
    @PreAuthorize("hasAuthority('DEBTOR_VIEW') or hasAuthority('CASE_VIEW')")
    CollateralView get(@PathVariable UUID id) {
        return collaterals.get(id);
    }

    @PutMapping("/collateral/{id}")
    @PreAuthorize("hasAuthority('COLLATERAL_MANAGE')")
    CollateralView update(@PathVariable UUID id, @Valid @RequestBody CollateralRequest request) {
        return collaterals.update(id, request.toCommand());
    }

    @PostMapping("/collateral/{id}/status")
    @PreAuthorize("hasAuthority('COLLATERAL_MANAGE')")
    CollateralView changeStatus(@PathVariable UUID id, @Valid @RequestBody StatusRequest request) {
        return collaterals.changeStatus(id, request.status(), request.reason());
    }

    @GetMapping("/collateral/{id}/appraisals")
    @PreAuthorize("hasAuthority('DEBTOR_VIEW') or hasAuthority('CASE_VIEW')")
    List<AppraisalView> appraisals(@PathVariable UUID id) {
        return collaterals.appraisals(id);
    }

    @PostMapping("/collateral/{id}/appraisals")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('COLLATERAL_MANAGE')")
    AppraisalView appraise(@PathVariable UUID id, @Valid @RequestBody AppraisalRequest request) {
        return collaterals.appraise(id, new AppraiseCommand(request.amount(), request.appraisalDate(), request.source(),
                request.notes()));
    }

    /** Na alteração, {@code type} e {@code externalId} são ignorados (imutáveis). */
    record CollateralRequest(@NotBlank @Size(max = 40) String type,
                             @Size(max = 80) String externalId,
                             @Size(max = 1000) String description,
                             @NotNull @Valid AssetRequest asset,
                             @Valid RegistrationRequest registration,
                             @Size(max = 500) Set<@NotNull UUID> titleIds) {

        CollateralCommand toCommand() {
            AssetCommand assetCommand = new AssetCommand(asset.kind(), asset.plate(), asset.renavam(), asset.chassis(),
                    asset.manufactureYear(), asset.modelYear(), asset.registryNumber(), asset.registryOffice(),
                    asset.municipality(), asset.state());
            RegistrationCommand registrationCommand = registration == null ? null
                    : new RegistrationCommand(registration.authority(), registration.number(), registration.date());
            return new CollateralCommand(type, externalId, description, assetCommand, registrationCommand,
                    titleIds == null ? Set.of() : titleIds);
        }
    }

    /** Identificação do bem: sem dados de pessoa física (o proprietário é uma parte da obrigação). */
    record AssetRequest(@NotBlank @Size(max = 20) String kind,
                        @Size(max = 10) String plate,
                        @Size(max = 14) String renavam,
                        @Size(max = 20) String chassis,
                        @Min(1900) @Max(2100) Integer manufactureYear,
                        @Min(1900) @Max(2100) Integer modelYear,
                        @Size(max = 40) String registryNumber,
                        @Size(max = 150) String registryOffice,
                        @Size(max = 100) String municipality,
                        @Size(max = 2) String state) {
    }

    record RegistrationRequest(@Size(max = 150) String authority, @Size(max = 60) String number,
                               @PastOrPresent LocalDate date) {
    }

    record StatusRequest(@NotBlank @Size(max = 20) String status, @NotBlank @Size(max = 500) String reason) {
    }

    record AppraisalRequest(@NotNull @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal amount,
                            @NotNull @PastOrPresent LocalDate appraisalDate,
                            @NotBlank @Size(max = 20) String source,
                            @Size(max = 500) String notes) {
    }
}
