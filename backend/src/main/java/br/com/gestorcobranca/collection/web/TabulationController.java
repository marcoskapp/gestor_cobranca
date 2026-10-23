package br.com.gestorcobranca.collection.web;

import br.com.gestorcobranca.collection.application.port.in.TabulationUseCases;
import br.com.gestorcobranca.collection.application.port.in.TabulationUseCases.TabulationCommand;
import br.com.gestorcobranca.collection.application.port.in.TabulationUseCases.TabulationView;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
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

import java.util.List;
import java.util.UUID;

/** Tabulações de interação configuráveis por assessoria. */
@RestController
@RequestMapping(ApiPaths.V1 + "/collection/tabulations")
class TabulationController {

    private final TabulationUseCases tabulations;

    TabulationController(TabulationUseCases tabulations) {
        this.tabulations = tabulations;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('CASE_VIEW') or hasAuthority('INTERACTION_REGISTER') or hasAuthority('TABULATION_MANAGE')")
    List<TabulationView> list(@RequestParam(defaultValue = "false") boolean activeOnly) {
        return tabulations.list(activeOnly);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('TABULATION_MANAGE')")
    TabulationView create(@Valid @RequestBody TabulationRequest request) {
        return tabulations.create(request.toCommand());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('TABULATION_MANAGE')")
    TabulationView update(@PathVariable UUID id, @Valid @RequestBody TabulationRequest request) {
        return tabulations.update(id, request.toCommand());
    }

    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('TABULATION_MANAGE')")
    TabulationView activate(@PathVariable UUID id) {
        return tabulations.activate(id);
    }

    @PostMapping("/{id}/deactivate")
    @PreAuthorize("hasAuthority('TABULATION_MANAGE')")
    TabulationView deactivate(@PathVariable UUID id) {
        return tabulations.deactivate(id);
    }

    record TabulationRequest(@Size(max = 40) String code,
                             @NotBlank @Size(max = 120) String name,
                             boolean effectiveContact,
                             boolean promise,
                             boolean callback,
                             boolean requiresNote,
                             int displayOrder) {

        TabulationCommand toCommand() {
            return new TabulationCommand(code, name, effectiveContact, promise, callback, requiresNote, displayOrder);
        }
    }
}
