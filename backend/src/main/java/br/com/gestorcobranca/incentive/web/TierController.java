package br.com.gestorcobranca.incentive.web;

import br.com.gestorcobranca.incentive.application.port.in.TierUseCases;
import br.com.gestorcobranca.incentive.application.port.in.TierUseCases.AdjustCommand;
import br.com.gestorcobranca.incentive.application.port.in.TierUseCases.MyTierView;
import br.com.gestorcobranca.incentive.application.port.in.TierUseCases.PersonView;
import br.com.gestorcobranca.incentive.application.port.in.TierUseCases.RoundDetail;
import br.com.gestorcobranca.incentive.application.port.in.TierUseCases.RoundSummary;
import br.com.gestorcobranca.incentive.application.port.in.TierUseCases.SettingsCommand;
import br.com.gestorcobranca.incentive.application.port.in.TierUseCases.SettingsView;
import br.com.gestorcobranca.shared.web.ApiPaths;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Classificação de desempenho do negociador (docs/tecnico/desenho/distribuicao-por-desempenho.md §11.2). O escopo de
 * pessoas (equipe ou todos) e a publicação com visão total são conferidos no caso de uso.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/incentive/tiers")
@Tag(name = "Incentive — classificação", description = "Níveis de desempenho dos negociadores com revisão humana")
class TierController {

    private static final String REVIEW = "hasAnyAuthority('TIER_REVIEW', 'TIER_MANAGE')";

    private final TierUseCases tiers;

    TierController(TierUseCases tiers) {
        this.tiers = tiers;
    }

    @GetMapping("/settings")
    @PreAuthorize("hasAuthority('TIER_MANAGE')")
    SettingsView settings() {
        return tiers.settings();
    }

    @PutMapping("/settings")
    @PreAuthorize("hasAuthority('TIER_MANAGE')")
    SettingsView updateSettings(@Valid @RequestBody SettingsRequest request) {
        return tiers.updateSettings(new SettingsCommand(request.windowDays(), request.minEvaluations(),
                request.minCases(), request.qualityGate(), request.matrix(), request.defaultTier(), request.periodicity(),
                request.reviewBusinessDays()));
    }

    @GetMapping("/rounds")
    @PreAuthorize(REVIEW)
    List<RoundSummary> rounds() {
        return tiers.rounds();
    }

    @GetMapping("/rounds/{id}")
    @PreAuthorize(REVIEW)
    RoundDetail round(@PathVariable UUID id) {
        return tiers.round(id);
    }

    /** Apurar agora: abre uma rodada para revisão (recusa se já houver uma). */
    @PostMapping("/rounds")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('TIER_MANAGE')")
    RoundSummary assessNow() {
        return tiers.assessNow();
    }

    @PutMapping("/rounds/{id}/people/{userId}")
    @PreAuthorize("hasAuthority('TIER_REVIEW')")
    PersonView adjust(@PathVariable UUID id, @PathVariable UUID userId, @Valid @RequestBody AdjustRequest request) {
        return tiers.adjust(id, userId, new AdjustCommand(request.tier(), request.reason()));
    }

    @PostMapping("/rounds/{id}/publish")
    @PreAuthorize("hasAuthority('TIER_REVIEW') and hasAuthority('PERFORMANCE_VIEW_ALL')")
    RoundSummary publish(@PathVariable UUID id) {
        return tiers.publish(id);
    }

    @GetMapping("/me")
    @PreAuthorize("hasAuthority('PERFORMANCE_VIEW_OWN')")
    MyTierView me() {
        return tiers.mine();
    }

    record SettingsRequest(@Min(30) @Max(365) int windowDays,
                           @Min(1) @Max(100) int minEvaluations,
                           @Min(1) @Max(10000) int minCases,
                           @NotNull @DecimalMin("0") @DecimalMax("10") BigDecimal qualityGate,
                           @NotEmpty @Size(min = 4, max = 4) Map<String, Map<String, String>> matrix,
                           @NotBlank @Pattern(regexp = "[ABCD]") String defaultTier,
                           @NotBlank @Pattern(regexp = "WEEKLY|MONTHLY") String periodicity,
                           @Min(1) @Max(20) int reviewBusinessDays) {
    }

    record AdjustRequest(@NotBlank @Pattern(regexp = "[ABCD]") String tier,
                         @NotBlank @Size(min = 10, max = 2000) String reason) {
    }
}
