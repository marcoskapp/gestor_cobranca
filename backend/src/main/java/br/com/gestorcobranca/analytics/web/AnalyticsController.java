package br.com.gestorcobranca.analytics.web;

import br.com.gestorcobranca.analytics.application.port.in.AnalyticsExportUseCases;
import br.com.gestorcobranca.analytics.application.port.in.AnalyticsExportUseCases.ExportFile;
import br.com.gestorcobranca.analytics.application.port.in.AnalyticsQueries;
import br.com.gestorcobranca.analytics.application.port.in.AnalyticsQueries.ConversionReport;
import br.com.gestorcobranca.analytics.application.port.in.AnalyticsQueries.DatasetFreshness;
import br.com.gestorcobranca.analytics.application.port.in.AnalyticsQueries.PortfolioReport;
import br.com.gestorcobranca.analytics.application.port.in.AnalyticsQueries.ProductivityReport;
import br.com.gestorcobranca.analytics.application.port.in.AnalyticsQueries.RecoveryReport;
import br.com.gestorcobranca.analytics.application.port.in.AnalyticsQueries.ReliabilityReport;
import br.com.gestorcobranca.analytics.application.port.in.AnalyticsRefreshUseCases;
import br.com.gestorcobranca.analytics.domain.model.ReportFilter;
import br.com.gestorcobranca.shared.web.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.InputStreamResource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Painéis e relatórios (F11, F18). Filtros comuns por query string, com valores repetidos para
 * listas ({@code ?creditorId=a&creditorId=b}). Definições dos indicadores em
 * docs/negocio/indicadores.md.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/analytics")
@Tag(name = "Analytics", description = "Painel operacional, recuperação, conversão, produtividade e confiabilidade")
class AnalyticsController {

    /** Dados por pessoa: mesmas permissões que dão escopo de pessoas em {@code AnalyticsAccess#peopleScope()}. */
    private static final String PEOPLE = "hasAnyAuthority('PERFORMANCE_VIEW_OWN', 'PERFORMANCE_VIEW_TEAM', "
            + "'PERFORMANCE_VIEW_ALL', 'COMMISSION_STATEMENT_MANAGE', 'COMMISSION_STATEMENT_APPROVE')";

    private final AnalyticsQueries queries;
    private final AnalyticsExportUseCases exports;
    private final AnalyticsRefreshUseCases refresh;

    AnalyticsController(AnalyticsQueries queries, AnalyticsExportUseCases exports, AnalyticsRefreshUseCases refresh) {
        this.queries = queries;
        this.exports = exports;
        this.refresh = refresh;
    }

    @GetMapping("/portfolio")
    @PreAuthorize("hasAnyAuthority('DASHBOARD_VIEW', 'REPORT_VIEW')")
    @Operation(summary = "Carteira, saldo vencido e elegível, casos por estado e fila pendente")
    PortfolioReport portfolio(FilterParams params) {
        return queries.portfolio(params.toFilter());
    }

    @GetMapping("/recovery")
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    @Operation(summary = "Valor recuperado (pago confirmado, líquido de estornos), taxa, tempo e custo")
    RecoveryReport recovery(FilterParams params, @RequestParam(required = false) String groupBy) {
        return queries.recovery(params.toFilter(), groupBy);
    }

    @GetMapping("/conversion")
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    @Operation(summary = "Funil contato → proposta → acordo → 1ª parcela e qualidade da safra")
    ConversionReport conversion(FilterParams params, @RequestParam(required = false) String groupBy) {
        return queries.conversion(params.toFilter(), groupBy);
    }

    @GetMapping("/productivity")
    @PreAuthorize(PEOPLE)
    @Operation(summary = "Produtividade por operador ou equipe (escopo próprio, equipe ou todos)")
    ProductivityReport productivity(FilterParams params, @RequestParam(required = false) String groupBy) {
        return queries.productivity(params.toFilter(), groupBy);
    }

    @GetMapping("/reliability")
    @PreAuthorize("hasAnyAuthority('DASHBOARD_VIEW', 'REPORT_VIEW')")
    @Operation(summary = "Falhas de integração e de mensagens, conciliação, jobs e importações")
    ReliabilityReport reliability(FilterParams params) {
        return queries.reliability(params.toFilter());
    }

    @GetMapping("/freshness")
    @PreAuthorize("hasAnyAuthority('DASHBOARD_VIEW', 'REPORT_VIEW', 'PERFORMANCE_VIEW_OWN', 'PERFORMANCE_VIEW_TEAM', "
            + "'PERFORMANCE_VIEW_ALL')")
    List<DatasetFreshness> freshness() {
        return queries.freshness();
    }

    @PostMapping("/refresh")
    @PreAuthorize("hasAnyAuthority('DASHBOARD_VIEW', 'REPORT_VIEW')")
    @Operation(summary = "Atualiza agora os dados dos painéis da assessoria (intervalo mínimo entre pedidos)")
    List<DatasetFreshness> refresh() {
        return refresh.refreshNow();
    }

    @GetMapping("/{report}/export")
    @PreAuthorize("hasAuthority('REPORT_EXPORT')")
    @Operation(summary = "Exporta o relatório (CSV ou XLSX) com os mesmos filtros; registrado no log de acesso")
    ResponseEntity<InputStreamResource> export(@PathVariable String report, FilterParams params,
                                               @RequestParam(required = false) String groupBy,
                                               @RequestParam String format) {
        ExportFile file = exports.export(report, params.toFilter(), groupBy, format);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-SHA256", file.sha256())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.size())
                .body(new InputStreamResource(file.content()));
    }

    /** Filtros comuns (vinculados da query string). */
    record FilterParams(@DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                        @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                        Set<UUID> creditorId, Set<UUID> portfolioId, Set<UUID> operatorId, Set<UUID> teamId,
                        Set<String> channel) {

        ReportFilter toFilter() {
            return new ReportFilter(from, to, creditorId, portfolioId, operatorId, teamId, channel);
        }
    }
}
