package br.com.gestorcobranca.compliance.web;

import br.com.gestorcobranca.compliance.application.port.in.DataProcessorUseCases;
import br.com.gestorcobranca.compliance.application.port.in.DataProcessorUseCases.ProcessorDetails;
import br.com.gestorcobranca.compliance.application.port.in.DataProcessorUseCases.ProcessorView;
import br.com.gestorcobranca.compliance.application.port.in.DataProcessorUseCases.RecipientInUseView;
import br.com.gestorcobranca.compliance.application.port.in.DataProcessorUseCases.RegisterProcessorCommand;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

/**
 * Cadastro de operadores e suboperadores (LGPD art. 37 e 39). Mesma permissão das bases legais e das solicitações de
 * titulares; alterar exige acesso a todos os credores (no serviço).
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/compliance/data-processors")
class DataProcessorController {

    private final DataProcessorUseCases processors;

    DataProcessorController(DataProcessorUseCases processors) {
        this.processors = processors;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('DATA_SUBJECT_REQUEST_MANAGE')")
    List<ProcessorView> list() {
        return processors.list();
    }

    /** Prestadores configurados na assessoria (contas de canal, telefonia, Pix, IA) ainda sem cadastro. */
    @GetMapping("/unregistered")
    @PreAuthorize("hasAuthority('DATA_SUBJECT_REQUEST_MANAGE')")
    List<RecipientInUseView> unregistered() {
        return processors.unregisteredRecipients();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('DATA_SUBJECT_REQUEST_MANAGE')")
    ProcessorView get(@PathVariable UUID id) {
        return processors.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('DATA_SUBJECT_REQUEST_MANAGE')")
    ProcessorView register(@Valid @RequestBody RegisterRequest request) {
        return processors.register(new RegisterProcessorCommand(request.providerKey(), request.details()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('DATA_SUBJECT_REQUEST_MANAGE')")
    ProcessorView update(@PathVariable UUID id, @Valid @RequestBody DetailsRequest request) {
        return processors.update(id, request.details());
    }

    record DetailsRequest(@NotBlank @Size(max = 150) String name, @NotBlank @Size(max = 20) String role,
                          @NotBlank @Size(max = 300) String purpose, @NotBlank @Size(max = 300) String dataCategories,
                          @NotBlank @Size(min = 2, max = 2) String countryCode, @Size(max = 40) String transferSafeguard,
                          @Size(max = 120) String contractReference, @NotNull LocalDate validFrom,
                          LocalDate validUntil, boolean active) {

        ProcessorDetails details() {
            return new ProcessorDetails(name, role, purpose, dataCategories, countryCode, transferSafeguard,
                    contractReference, validFrom, validUntil, active);
        }
    }

    record RegisterRequest(@NotBlank @Size(max = 80) String providerKey, @NotBlank @Size(max = 150) String name,
                           @NotBlank @Size(max = 20) String role, @NotBlank @Size(max = 300) String purpose,
                           @NotBlank @Size(max = 300) String dataCategories,
                           @NotBlank @Size(min = 2, max = 2) String countryCode,
                           @Size(max = 40) String transferSafeguard, @Size(max = 120) String contractReference,
                           @NotNull LocalDate validFrom, LocalDate validUntil, boolean active) {

        ProcessorDetails details() {
            return new ProcessorDetails(name, role, purpose, dataCategories, countryCode, transferSafeguard,
                    contractReference, validFrom, validUntil, active);
        }
    }
}
