package br.com.gestorcobranca.collection.web;

import br.com.gestorcobranca.collection.application.port.in.CollectionSettingsUseCases;
import br.com.gestorcobranca.collection.application.port.in.CollectionSettingsUseCases.SettingsCommand;
import br.com.gestorcobranca.collection.application.port.in.CollectionSettingsUseCases.SettingsView;
import br.com.gestorcobranca.collection.application.port.in.CollectionSettingsUseCases.TierSettingsCommand;
import br.com.gestorcobranca.collection.application.port.in.CollectionSettingsUseCases.TierSettingsView;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

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

    /**
     * Parâmetros da distribuição por nível de desempenho. Leitura para quem distribui ou configura filas; alteração
     * com {@code QUEUE_MANAGE} e acesso a todos os credores (parâmetro da assessoria, como os demais).
     */
    @GetMapping("/tier-distribution")
    @PreAuthorize("hasAuthority('CASE_ASSIGN') or hasAuthority('QUEUE_MANAGE')")
    TierSettingsView tierSettings() {
        return settings.tierSettings();
    }

    @PutMapping("/tier-distribution")
    @PreAuthorize("hasAuthority('QUEUE_MANAGE')")
    TierSettingsView updateTierSettings(@Valid @RequestBody TierSettingsRequest request) {
        return settings.updateTierSettings(new TierSettingsCommand(request.capacityPercent(), request.developmentShare(),
                request.defaultTier()));
    }

    record TierSettingsRequest(@NotNull @Size(min = 4, max = 4) Map<@NotBlank String, @NotNull @Min(50) @Max(200) Integer> capacityPercent,
                               @Min(0) @Max(50) int developmentShare,
                               @NotBlank @Size(max = 1) String defaultTier) {
    }

    record SettingsRequest(@Min(0) @Max(30) int promiseToleranceDays,
                           @Min(1) @Max(180) int promiseMaxDays,
                           @Min(0) @Max(1440) int callbackToleranceMinutes,
                           @Min(1) @Max(90) int disputeResponseDays) {
    }
}
