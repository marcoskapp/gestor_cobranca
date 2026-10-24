package br.com.gestorcobranca.scoring.web;

import br.com.gestorcobranca.scoring.application.port.in.CaseScoreUseCases;
import br.com.gestorcobranca.scoring.application.port.in.CaseScoreUseCases.CaseScoreView;
import br.com.gestorcobranca.scoring.application.port.in.CaseScoreUseCases.HistoryEntry;
import br.com.gestorcobranca.scoring.application.port.in.CaseScoreUseCases.ReviewCommand;
import br.com.gestorcobranca.scoring.application.port.in.CaseScoreUseCases.ReviewView;
import br.com.gestorcobranca.shared.web.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Score do caso, explicação, histórico, recálculo e revisão (LGPD art. 20). */
@RestController
@RequestMapping(ApiPaths.V1 + "/scoring/cases/{caseId}")
@Tag(name = "Scoring — casos", description = "Score de propensão do caso e sua explicação")
class CaseScoreController {

    private final CaseScoreUseCases scores;

    CaseScoreController(CaseScoreUseCases scores) {
        this.scores = scores;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCORING_VIEW')")
    @Operation(summary = "Score vigente com os fatores (contribuição por feature) e o aviso de finalidade")
    CaseScoreView get(@PathVariable UUID caseId) {
        return scores.get(caseId);
    }

    @GetMapping("/history")
    @PreAuthorize("hasAuthority('SCORING_VIEW')")
    @Operation(summary = "Score do caso nos snapshots, com o desfecho quando apurado")
    List<HistoryEntry> history(@PathVariable UUID caseId) {
        return scores.history(caseId);
    }

    @PostMapping("/recalculate")
    @PreAuthorize("hasAuthority('SCORING_VIEW') and hasAuthority('CASE_WORK')")
    @Operation(summary = "Recalcula o score agora com o campeão vigente")
    CaseScoreView recalculate(@PathVariable UUID caseId) {
        return scores.recalculate(caseId);
    }

    @PostMapping("/reviews")
    @PreAuthorize("hasAuthority('SCORING_MANAGE')")
    @Operation(summary = "Revisão do score (recálculo registrado com o anterior e o novo)")
    ReviewView review(@PathVariable UUID caseId, @Valid @RequestBody ReviewRequest request) {
        return scores.review(caseId, new ReviewCommand(request.origin(), request.requestReference()));
    }

    @GetMapping("/reviews")
    @PreAuthorize("hasAuthority('SCORING_VIEW')")
    @Operation(summary = "Revisões do score do caso")
    List<ReviewView> reviews(@PathVariable UUID caseId) {
        return scores.reviews(caseId);
    }

    record ReviewRequest(@NotBlank @Pattern(regexp = "DATA_SUBJECT_REQUEST|OPERATION_REQUEST|QUALITY_CHECK") String origin,
                         UUID requestReference) {
    }
}
