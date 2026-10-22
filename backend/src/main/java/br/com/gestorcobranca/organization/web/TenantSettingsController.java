package br.com.gestorcobranca.organization.web;

import br.com.gestorcobranca.organization.application.port.in.TenantAdministration;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration.UpdateTenantSettingsCommand;
import br.com.gestorcobranca.organization.application.port.in.TenantView;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalTime;

/** Configurações da assessoria do usuário autenticado. */
@RestController
@RequestMapping(ApiPaths.V1 + "/organization/tenant")
class TenantSettingsController {

    private final TenantAdministration tenantAdministration;

    TenantSettingsController(TenantAdministration tenantAdministration) {
        this.tenantAdministration = tenantAdministration;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    TenantView current() {
        return tenantAdministration.currentTenant();
    }

    @PutMapping
    @PreAuthorize("hasAuthority('ORG_SETTINGS_MANAGE')")
    TenantView update(@Valid @RequestBody TenantSettingsRequest request) {
        return tenantAdministration.updateCurrentTenant(new UpdateTenantSettingsCommand(request.legalName(),
                request.tradeName(), request.contactWindowStart(), request.contactWindowEnd(),
                request.contactOnSaturday(), request.contactOnSunday()));
    }

    record TenantSettingsRequest(
            @NotBlank @Size(max = 200) String legalName,
            @NotBlank @Size(max = 200) String tradeName,
            @NotNull LocalTime contactWindowStart,
            @NotNull LocalTime contactWindowEnd,
            boolean contactOnSaturday,
            boolean contactOnSunday) {
    }
}
