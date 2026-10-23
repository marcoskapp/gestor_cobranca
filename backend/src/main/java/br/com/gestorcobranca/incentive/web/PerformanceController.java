package br.com.gestorcobranca.incentive.web;

import br.com.gestorcobranca.incentive.application.port.in.AttributionQueries;
import br.com.gestorcobranca.incentive.application.port.in.AttributionQueries.AttributionView;
import br.com.gestorcobranca.incentive.application.port.in.PerformanceQueries;
import br.com.gestorcobranca.incentive.application.port.in.PerformanceQueries.IndicatorQuery;
import br.com.gestorcobranca.incentive.application.port.in.PerformanceQueries.IndicatorReport;
import br.com.gestorcobranca.shared.web.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/** Indicadores dos negociadores (§2) e atribuição de crédito dos acordos (§3). */
@RestController
@RequestMapping(ApiPaths.V1 + "/incentive")
@Tag(name = "Incentive — desempenho", description = "Indicadores do negociador e atribuição de crédito")
class PerformanceController {

    private final PerformanceQueries performance;
    private final AttributionQueries attributions;

    PerformanceController(PerformanceQueries performance, AttributionQueries attributions) {
        this.performance = performance;
        this.attributions = attributions;
    }

    @GetMapping("/performance")
    @PreAuthorize("hasAnyAuthority('PERFORMANCE_VIEW_OWN', 'PERFORMANCE_VIEW_TEAM', 'PERFORMANCE_VIEW_ALL')")
    @Operation(summary = "Indicadores por negociador, equipe, credor, carteira, faixa de atraso ou canal")
    IndicatorReport indicators(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                               @RequestParam(required = false) UUID userId,
                               @RequestParam(required = false) UUID teamId,
                               @RequestParam(required = false) UUID creditorId,
                               @RequestParam(required = false) UUID portfolioId,
                               @RequestParam(required = false) String delayBucket,
                               @RequestParam(required = false) String channel,
                               @RequestParam(defaultValue = "NONE") String groupBy) {
        return performance.indicators(new IndicatorQuery(from, to, userId, teamId, creditorId, portfolioId, delayBucket,
                channel, groupBy));
    }

    @GetMapping("/attributions/{agreementId}")
    @PreAuthorize("hasAnyAuthority('PERFORMANCE_VIEW_OWN', 'PERFORMANCE_VIEW_TEAM', 'PERFORMANCE_VIEW_ALL', 'AGREEMENT_VIEW')")
    @Operation(summary = "Participantes e créditos registrados na formalização do acordo")
    AttributionView attribution(@PathVariable UUID agreementId) {
        return attributions.byAgreement(agreementId);
    }
}
