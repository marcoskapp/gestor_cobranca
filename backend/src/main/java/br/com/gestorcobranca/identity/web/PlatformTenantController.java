package br.com.gestorcobranca.identity.web;

import br.com.gestorcobranca.identity.application.port.in.TenantProvisioning;
import br.com.gestorcobranca.identity.application.port.in.TenantProvisioning.AdminInvitation;
import br.com.gestorcobranca.identity.application.port.in.TenantProvisioning.ProvisionTenantCommand;
import br.com.gestorcobranca.identity.application.port.in.TenantProvisioning.ProvisionedTenant;
import br.com.gestorcobranca.organization.application.port.in.TenantAdministration;
import br.com.gestorcobranca.organization.application.port.in.TenantView;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Administração da plataforma SaaS: provisionamento e status das assessorias. */
@RestController
@RequestMapping(ApiPaths.V1 + "/platform/tenants")
@PreAuthorize("hasAuthority('TENANT_MANAGE')")
class PlatformTenantController {

    private final TenantProvisioning provisioning;
    private final TenantAdministration tenantAdministration;

    PlatformTenantController(TenantProvisioning provisioning, TenantAdministration tenantAdministration) {
        this.provisioning = provisioning;
        this.tenantAdministration = tenantAdministration;
    }

    @GetMapping
    List<TenantView> list() {
        return tenantAdministration.listTenants();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    ProvisionedTenant provision(@Valid @RequestBody ProvisionRequest request) {
        return provisioning.provision(new ProvisionTenantCommand(request.slug(), request.legalName(), request.tradeName(),
                request.cnpj(), request.adminName(), request.adminEmail()));
    }

    @PostMapping("/{id}/suspend")
    TenantView suspend(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
        return tenantAdministration.changeTenantStatus(id, false, request.reason());
    }

    @PostMapping("/{id}/reactivate")
    TenantView reactivate(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
        return tenantAdministration.changeTenantStatus(id, true, request.reason());
    }

    /**
     * Reenvia o convite do administrador inicial enquanto a assessoria não tem administrador ativo
     * (link de ativação novo, 72 h, uso único; o anterior deixa de valer).
     */
    @PostMapping("/{id}/admin-invitation")
    AdminInvitation resendAdminInvitation(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
        return provisioning.resendAdminInvitation(id, request.reason());
    }

    record ProvisionRequest(@NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{1,48}[a-z0-9]") String slug,
                            @NotBlank @Size(max = 200) String legalName, @NotBlank @Size(max = 200) String tradeName,
                            @NotBlank @Size(max = 18) String cnpj, @NotBlank @Size(max = 150) String adminName,
                            @NotBlank @Email @Size(max = 200) String adminEmail) {
    }

    record ReasonRequest(@NotBlank @Size(max = 500) String reason) {
    }
}
