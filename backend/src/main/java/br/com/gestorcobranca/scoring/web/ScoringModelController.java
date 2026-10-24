package br.com.gestorcobranca.scoring.web;

import br.com.gestorcobranca.scoring.application.port.in.ModelUseCases;
import br.com.gestorcobranca.scoring.application.port.in.ModelUseCases.BinInput;
import br.com.gestorcobranca.scoring.application.port.in.ModelUseCases.DecisionCommand;
import br.com.gestorcobranca.scoring.application.port.in.ModelUseCases.DecisionView;
import br.com.gestorcobranca.scoring.application.port.in.ModelUseCases.FeatureCatalog;
import br.com.gestorcobranca.scoring.application.port.in.ModelUseCases.ModelView;
import br.com.gestorcobranca.scoring.application.port.in.ModelUseCases.RuleInput;
import br.com.gestorcobranca.scoring.application.port.in.ModelUseCases.ScorecardCommand;
import br.com.gestorcobranca.scoring.application.port.in.ModelUseCases.TrainingRequest;
import br.com.gestorcobranca.shared.web.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/** Modelos versionados, treino, scorecards, promoção manual e decisões. */
@RestController
@RequestMapping(ApiPaths.V1 + "/scoring")
@Tag(name = "Scoring — modelos", description = "Versões de modelo, validação e governança campeão × desafiante")
class ScoringModelController {

    private final ModelUseCases models;

    ScoringModelController(ModelUseCases models) {
        this.models = models;
    }

    @GetMapping("/models")
    @PreAuthorize("hasAuthority('SCORING_VIEW')")
    @Operation(summary = "Versões de modelo (mais recente primeiro)")
    List<ModelView> list() {
        return models.list();
    }

    @GetMapping("/models/{id}")
    @PreAuthorize("hasAuthority('SCORING_VIEW')")
    @Operation(summary = "Versão de modelo com definição e métricas de validação")
    ModelView get(@PathVariable UUID id) {
        return models.get(id);
    }

    @PostMapping("/models/training")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('SCORING_MANAGE')")
    @Operation(summary = "Pede um treino da regressão logística (assíncrono; acompanhe pela execução)")
    TrainingRequest train() {
        return models.train();
    }

    @PostMapping("/models/scorecards")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('SCORING_MANAGE')")
    @Operation(summary = "Cria um scorecard configurado (entra como desafiante)")
    ModelView createScorecard(@Valid @RequestBody ScorecardRequest request) {
        return models.createScorecard(new ScorecardCommand(request.description(), request.basePoints(), request.pdo(),
                request.anchorPoints(), request.anchorOdds(), request.rules().stream()
                .map(r -> new RuleInput(r.feature(), r.bins().stream()
                        .map(b -> new BinInput(b.from(), b.to(), b.points())).toList(), r.missingPoints()))
                .toList()));
    }

    @PostMapping("/models/{id}/promote")
    @PreAuthorize("hasAuthority('SCORING_MANAGE')")
    @Operation(summary = "Promove o desafiante a campeão (manual, com justificativa)")
    ModelView promote(@PathVariable UUID id, @Valid @RequestBody DecisionRequest request) {
        return models.promote(id, new DecisionCommand(request.justification()));
    }

    @PostMapping("/models/{id}/reject")
    @PreAuthorize("hasAuthority('SCORING_MANAGE')")
    @Operation(summary = "Rejeita o desafiante (com justificativa)")
    ModelView reject(@PathVariable UUID id, @Valid @RequestBody DecisionRequest request) {
        return models.reject(id, new DecisionCommand(request.justification()));
    }

    @GetMapping("/decisions")
    @PreAuthorize("hasAuthority('SCORING_VIEW')")
    @Operation(summary = "Decisões de promoção e rejeição registradas")
    List<DecisionView> decisions() {
        return models.decisions();
    }

    @GetMapping("/features")
    @PreAuthorize("hasAuthority('SCORING_VIEW')")
    @Operation(summary = "Features permitidas no score e o que nunca é usado")
    FeatureCatalog features() {
        return models.features();
    }

    record ScorecardRequest(@Size(max = 200) String description, int basePoints, int pdo, int anchorPoints,
                            @NotNull @DecimalMin("0.0001") @DecimalMax("999") BigDecimal anchorOdds,
                            @NotEmpty @Size(max = 30) List<@Valid RuleRequest> rules) {
    }

    record RuleRequest(@NotBlank String feature, @NotNull @Size(max = 20) List<@Valid BinRequest> bins,
                       int missingPoints) {
    }

    record BinRequest(BigDecimal from, BigDecimal to, int points) {
    }

    record DecisionRequest(@NotBlank @Size(min = 10, max = 1000) String justification) {
    }
}
