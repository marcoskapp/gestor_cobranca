package br.com.gestorcobranca.organization.web;

import br.com.gestorcobranca.organization.application.port.in.BusinessUnitUseCases;
import br.com.gestorcobranca.organization.application.port.in.BusinessUnitUseCases.BusinessUnitView;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.V1 + "/organization/units")
class BusinessUnitController {

    private final BusinessUnitUseCases units;

    BusinessUnitController(BusinessUnitUseCases units) {
        this.units = units;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    List<BusinessUnitView> list() {
        return units.list();
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    BusinessUnitView get(@PathVariable UUID id) {
        return units.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ORG_SETTINGS_MANAGE')")
    BusinessUnitView create(@Valid @RequestBody CreateUnitRequest request) {
        return units.create(request.code(), request.name());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('ORG_SETTINGS_MANAGE')")
    BusinessUnitView update(@PathVariable UUID id, @Valid @RequestBody UpdateUnitRequest request) {
        return units.update(id, request.name(), request.active());
    }

    record CreateUnitRequest(@NotBlank @Size(max = 30) @Pattern(regexp = "[A-Za-z0-9_-]+") String code,
                             @NotBlank @Size(max = 120) String name) {
    }

    record UpdateUnitRequest(@NotBlank @Size(max = 120) String name, boolean active) {
    }
}
