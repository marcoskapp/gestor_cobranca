package br.com.gestorcobranca.identity.web;

import br.com.gestorcobranca.identity.application.port.in.RoleManagementUseCases;
import br.com.gestorcobranca.identity.application.port.in.RoleManagementUseCases.PermissionView;
import br.com.gestorcobranca.identity.application.port.in.RoleManagementUseCases.RoleCommand;
import br.com.gestorcobranca.identity.application.port.in.RoleManagementUseCases.RoleView;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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

import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.V1 + "/roles")
class RoleController {

    private final RoleManagementUseCases roles;

    RoleController(RoleManagementUseCases roles) {
        this.roles = roles;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('USER_VIEW', 'ROLE_MANAGE')")
    List<RoleView> list() {
        return roles.list();
    }

    @GetMapping("/permissions")
    @PreAuthorize("hasAuthority('ROLE_MANAGE')")
    List<PermissionView> permissions() {
        return roles.permissionCatalog();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('USER_VIEW', 'ROLE_MANAGE')")
    RoleView get(@PathVariable UUID id) {
        return roles.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ROLE_MANAGE')")
    RoleView create(@Valid @RequestBody RoleRequest request) {
        return roles.create(request.toCommand());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ROLE_MANAGE')")
    RoleView update(@PathVariable UUID id, @Valid @RequestBody RoleRequest request) {
        return roles.update(id, request.toCommand());
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('ROLE_MANAGE')")
    RoleView activate(@PathVariable UUID id) {
        return roles.changeStatus(id, true);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('ROLE_MANAGE')")
    RoleView deactivate(@PathVariable UUID id) {
        return roles.changeStatus(id, false);
    }

    record RoleRequest(@Pattern(regexp = "[A-Z][A-Z0-9_]{1,39}") String code, @NotBlank @Size(max = 100) String name,
                       @Size(max = 500) String description, @NotEmpty Set<String> permissions) {

        RoleCommand toCommand() {
            return new RoleCommand(code, name, description, permissions);
        }
    }
}
