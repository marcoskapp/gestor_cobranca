package br.com.gestorcobranca.publicapi.web.admin;

import br.com.gestorcobranca.publicapi.application.port.in.PublicApiBillingUseCases;
import br.com.gestorcobranca.publicapi.application.port.in.PublicApiBillingUseCases.StatementView;
import br.com.gestorcobranca.publicapi.application.port.in.PublicApiUsageUseCases;
import br.com.gestorcobranca.publicapi.application.port.in.PublicApiUsageUseCases.UsageReport;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

/**
 * Consumo e extratos por ciclo da API pública (telas internas da assessoria). Os preços ficam no contrato do credor
 * ({@code /api/v1/contracts/{id}/integration-pricing}, módulo organization).
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/public-api")
class PublicApiUsageController {

    private final PublicApiUsageUseCases usage;
    private final PublicApiBillingUseCases billing;

    PublicApiUsageController(PublicApiUsageUseCases usage, PublicApiBillingUseCases billing) {
        this.usage = usage;
        this.billing = billing;
    }

    @GetMapping("/usage")
    @PreAuthorize("hasAuthority('PUBLIC_API_USAGE_VIEW')")
    UsageReport report(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       @RequestParam(required = false) UUID creditorId, @RequestParam(required = false) UUID tokenId) {
        return usage.report(from, to, creditorId, tokenId);
    }

    @GetMapping("/usage/export")
    @PreAuthorize("hasAuthority('PUBLIC_API_USAGE_VIEW') and hasAuthority('REPORT_EXPORT')")
    void export(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                @RequestParam(required = false) UUID creditorId, @RequestParam(required = false) UUID tokenId,
                HttpServletResponse response) throws IOException {
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename("consumo-api-publica-%s-%s.csv".formatted(from, to)).build().toString());
        usage.exportCsv(from, to, creditorId, tokenId, response.getOutputStream());
    }

    @GetMapping("/statements")
    @PreAuthorize("hasAuthority('PUBLIC_API_USAGE_VIEW')")
    PageResponse<StatementView> statements(@RequestParam(required = false) UUID creditorId,
                                           @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
                                           @RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer size) {
        return billing.statements(creditorId, month, PageQuery.of(page, size));
    }

    @GetMapping("/statements/preview")
    @PreAuthorize("hasAuthority('PUBLIC_API_USAGE_VIEW')")
    StatementView preview(@RequestParam UUID creditorId) {
        return billing.preview(creditorId);
    }

    @PostMapping("/statements")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PUBLIC_API_BILLING_MANAGE')")
    StatementView close(@Valid @RequestBody CloseStatementRequest r) {
        return billing.close(r.creditorId(), r.periodStart(), r.reason());
    }

    @GetMapping("/statements/{id}")
    @PreAuthorize("hasAuthority('PUBLIC_API_USAGE_VIEW')")
    StatementView statement(@PathVariable UUID id) {
        return billing.statement(id);
    }

    @GetMapping("/statements/{id}/export")
    @PreAuthorize("hasAuthority('PUBLIC_API_USAGE_VIEW') and hasAuthority('REPORT_EXPORT')")
    void exportStatement(@PathVariable UUID id, HttpServletResponse response) throws IOException {
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename("extrato-api-publica-%s.csv".formatted(id)).build().toString());
        billing.exportStatement(id, response.getOutputStream());
    }

    /**
     * @param periodStart nulo = fecha o próximo ciclo encerrado; início de um ciclo já fechado = nova revisão (exige
     *                    {@code reason})
     */
    record CloseStatementRequest(@NotNull UUID creditorId, LocalDate periodStart, @Size(max = 500) String reason) {
    }
}
