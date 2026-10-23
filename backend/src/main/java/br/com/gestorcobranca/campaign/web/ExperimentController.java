package br.com.gestorcobranca.campaign.web;

import br.com.gestorcobranca.campaign.application.port.in.ExperimentUseCases;
import br.com.gestorcobranca.campaign.application.port.in.ExperimentUseCases.ExperimentInput;
import br.com.gestorcobranca.campaign.application.port.in.ExperimentUseCases.ExperimentResults;
import br.com.gestorcobranca.campaign.application.port.in.ExperimentUseCases.ExperimentView;
import br.com.gestorcobranca.campaign.domain.model.ExperimentVariant;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Testes A/B das campanhas (F25). */
@RestController
@RequestMapping(ApiPaths.V1 + "/campaign")
class ExperimentController {

    private static final String READ = "hasAuthority('EXPERIMENT_MANAGE') or hasAuthority('CAMPAIGN_VIEW')";

    private final ExperimentUseCases experiments;

    ExperimentController(ExperimentUseCases experiments) {
        this.experiments = experiments;
    }

    @GetMapping("/campaigns/{campaignId}/experiments")
    @PreAuthorize(READ)
    List<ExperimentView> list(@PathVariable UUID campaignId) {
        return experiments.list(campaignId);
    }

    @PostMapping("/campaigns/{campaignId}/experiments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('EXPERIMENT_MANAGE')")
    ExperimentView create(@PathVariable UUID campaignId, @Valid @RequestBody ExperimentRequest request) {
        return experiments.create(campaignId, request.toInput());
    }

    @GetMapping("/experiments/{id}")
    @PreAuthorize(READ)
    ExperimentView get(@PathVariable UUID id) {
        return experiments.get(id);
    }

    @PutMapping("/experiments/{id}")
    @PreAuthorize("hasAuthority('EXPERIMENT_MANAGE')")
    ExperimentView update(@PathVariable UUID id, @Valid @RequestBody ExperimentRequest request) {
        return experiments.update(id, request.toInput());
    }

    @PostMapping("/experiments/{id}/start")
    @PreAuthorize("hasAuthority('EXPERIMENT_MANAGE')")
    ExperimentView start(@PathVariable UUID id) {
        return experiments.start(id);
    }

    @PostMapping("/experiments/{id}/stop")
    @PreAuthorize("hasAuthority('EXPERIMENT_MANAGE')")
    ExperimentView stop(@PathVariable UUID id, @Valid @RequestBody StopRequest request) {
        return experiments.stop(id, request.reason());
    }

    @GetMapping("/experiments/{id}/results")
    @PreAuthorize(READ)
    ExperimentResults results(@PathVariable UUID id, @RequestParam(defaultValue = "true") boolean mature) {
        return experiments.results(id, mature);
    }

    record ExperimentRequest(@NotBlank @Size(max = 120) String name, @Size(max = 500) String hypothesis,
                             String primaryMetric, BigDecimal controlShare,
                             @NotEmpty List<ExperimentVariant> variants, Integer observationDays,
                             Integer minSamplePerArm) {

        ExperimentInput toInput() {
            return new ExperimentInput(name, hypothesis, primaryMetric, controlShare, variants, observationDays,
                    minSamplePerArm);
        }
    }

    record StopRequest(@NotBlank @Size(max = 300) String reason) {
    }
}
