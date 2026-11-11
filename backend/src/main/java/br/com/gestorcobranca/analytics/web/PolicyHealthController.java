package br.com.gestorcobranca.analytics.web;

import br.com.gestorcobranca.analytics.application.port.in.PolicyHealthQueries;
import br.com.gestorcobranca.analytics.application.port.in.PolicyHealthQueries.PolicyHealthFilter;
import br.com.gestorcobranca.analytics.application.port.in.PolicyHealthQueries.PolicyHealthView;
import br.com.gestorcobranca.shared.web.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Pattern;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/** Saúde da política de negociação: resultados por recorte e sugestões de revisão (decisão 1087). */
@RestController
@RequestMapping(ApiPaths.V1 + "/analytics/policy-health")
@Tag(name = "Analytics — saúde da política", description = "Resultados da política por carteira e faixa de atraso, com sugestões")
class PolicyHealthController {

    private final PolicyHealthQueries queries;

    PolicyHealthController(PolicyHealthQueries queries) {
        this.queries = queries;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('POLICY_MANAGE', 'REPORT_VIEW')")
    @Operation(summary = "Quebra precoce, pago sobre acordado, uso do desconto e portal por recorte, com sugestões")
    PolicyHealthView health(@RequestParam UUID creditorId,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                            @RequestParam(required = false) @Pattern(regexp = "V0_1K|V1K_5K|V5K_20K|V20K_PLUS") String valueBand) {
        return queries.health(new PolicyHealthFilter(creditorId, from, to, valueBand));
    }
}
