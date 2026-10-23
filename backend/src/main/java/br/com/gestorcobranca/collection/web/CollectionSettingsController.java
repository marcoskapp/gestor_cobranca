package br.com.gestorcobranca.collection.web;

import br.com.gestorcobranca.collection.application.port.in.CollectionSettingsUseCases;
import br.com.gestorcobranca.collection.application.port.in.CollectionSettingsUseCases.SettingsCommand;
import br.com.gestorcobranca.collection.application.port.in.CollectionSettingsUseCases.SettingsView;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Parâmetros da operação de cobrança da assessoria. */
@RestController
@RequestMapping(ApiPaths.V1 + "/collection/settings")
class CollectionSettingsController {

    private final CollectionSettingsUseCases settings;

    CollectionSettingsController(CollectionSettingsUseCases settings) {
        this.settings = settings;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('CASE_VIEW') or hasAuthority('QUEUE_MANAGE')")
    SettingsView get() {
        return settings.get();
    }

    @PutMapping
    @PreAuthorize("hasAuthority('QUEUE_MANAGE')")
    SettingsView update(@Valid @RequestBody SettingsRequest request) {
        return settings.update(new SettingsCommand(request.promiseToleranceDays(), request.promiseMaxDays(),
                request.callbackToleranceMinutes(), request.disputeResponseDays()));
    }

    record SettingsRequest(@Min(0) @Max(30) int promiseToleranceDays,
                           @Min(1) @Max(180) int promiseMaxDays,
                           @Min(0) @Max(1440) int callbackToleranceMinutes,
                           @Min(1) @Max(90) int disputeResponseDays) {
    }
}
