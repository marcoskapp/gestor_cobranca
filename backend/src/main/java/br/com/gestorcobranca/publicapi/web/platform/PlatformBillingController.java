package br.com.gestorcobranca.publicapi.web.platform;

import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases.CreatePolicyCommand;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases.OverviewView;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases.PolicyTermsCommand;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases.PolicyView;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases.PositionView;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases.StatementView;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases.TierView;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/**
 * Faturamento da plataforma às assessorias (área da plataforma): consolidado, política por assessoria, posição do
 * ciclo em andamento e extratos. A cobrança da assessoria ao credor continua em {@code /api/v1/public-api}.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/platform/billing")
class PlatformBillingController {

    private final PlatformBillingUseCases billing;

    PlatformBillingController(PlatformBillingUseCases billing) {
        this.billing = billing;
    }

    @GetMapping("/overview")
    @PreAuthorize("hasAuthority('PLATFORM_BILLING_VIEW')")
    OverviewView overview() {
        return billing.overview();
    }

    @GetMapping("/tenants/{tenantId}/policy")
    @PreAuthorize("hasAuthority('PLATFORM_BILLING_VIEW')")
    PolicyView policy(@PathVariable UUID tenantId) {
        return billing.policy(tenantId);
    }

    @PostMapping("/tenants/{tenantId}/policy")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PLATFORM_BILLING_MANAGE')")
    PolicyView createPolicy(@PathVariable UUID tenantId, @Valid @RequestBody CreatePolicyRequest r) {
        return billing.createPolicy(tenantId, new CreatePolicyCommand(r.startDate(), r.endDate(), r.terms().command(),
                r.reason()));
    }

    @PostMapping("/tenants/{tenantId}/policy/versions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PLATFORM_BILLING_MANAGE')")
    PolicyView addVersion(@PathVariable UUID tenantId, @Valid @RequestBody AddVersionRequest r) {
        return billing.addVersion(tenantId, r.terms().command(), r.reason());
    }

    @PutMapping("/tenants/{tenantId}/policy/term")
    @PreAuthorize("hasAuthority('PLATFORM_BILLING_MANAGE')")
    PolicyView changeTerm(@PathVariable UUID tenantId, @Valid @RequestBody ChangeTermRequest r) {
        return billing.changeTerm(tenantId, r.startDate(), r.endDate(), r.reason());
    }

    @GetMapping("/tenants/{tenantId}/position")
    @PreAuthorize("hasAuthority('PLATFORM_BILLING_VIEW')")
    PositionView position(@PathVariable UUID tenantId) {
        return billing.position(tenantId);
    }

    @GetMapping("/tenants/{tenantId}/statements/preview")
    @PreAuthorize("hasAuthority('PLATFORM_BILLING_VIEW')")
    StatementView preview(@PathVariable UUID tenantId) {
        return billing.preview(tenantId);
    }

    @GetMapping("/tenants/{tenantId}/statements")
    @PreAuthorize("hasAuthority('PLATFORM_BILLING_VIEW')")
    PageResponse<StatementView> statements(@PathVariable UUID tenantId,
                                           @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
                                           @RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer size) {
        return billing.statements(tenantId, month, PageQuery.of(page, size));
    }

    @PostMapping("/tenants/{tenantId}/statements")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PLATFORM_BILLING_MANAGE')")
    StatementView close(@PathVariable UUID tenantId, @Valid @RequestBody CloseRequest r) {
        return billing.close(tenantId, r.periodStart(), r.reason());
    }

    @GetMapping("/tenants/{tenantId}/statements/{id}")
    @PreAuthorize("hasAuthority('PLATFORM_BILLING_VIEW')")
    StatementView statement(@PathVariable UUID tenantId, @PathVariable UUID id) {
        return billing.statement(tenantId, id);
    }

    @GetMapping("/tenants/{tenantId}/statements/{id}/export")
    @PreAuthorize("hasAuthority('PLATFORM_BILLING_VIEW')")
    void export(@PathVariable UUID tenantId, @PathVariable UUID id, HttpServletResponse response) throws IOException {
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename("extrato-plataforma-%s.csv".formatted(id)).build().toString());
        billing.exportStatement(tenantId, id, response.getOutputStream());
    }

    record TierRequest(@PositiveOrZero Long upTo, @NotNull @PositiveOrZero BigDecimal unitPrice) {
    }

    /** {@code pricePerRequest} nulo quando há faixas de volume; faixas vazias = preço único. */
    record TermsRequest(@Min(1) @Max(31) int closingDay, @NotNull @PositiveOrZero BigDecimal monthlyFee,
                        @PositiveOrZero long includedRequests, @PositiveOrZero BigDecimal pricePerRequest,
                        @Size(max = 10) List<@Valid @NotNull TierRequest> requestTiers,
                        @PositiveOrZero long includedMbIn, @NotNull @PositiveOrZero BigDecimal pricePerMbIn,
                        @PositiveOrZero long includedMbOut, @NotNull @PositiveOrZero BigDecimal pricePerMbOut,
                        @PositiveOrZero long includedRecords, @NotNull @PositiveOrZero BigDecimal pricePerRecord) {

        PolicyTermsCommand command() {
            return new PolicyTermsCommand(closingDay, monthlyFee, includedRequests, pricePerRequest,
                    requestTiers == null ? List.of() : requestTiers.stream()
                            .map(t -> new TierView(t.upTo(), t.unitPrice())).toList(),
                    includedMbIn, pricePerMbIn, includedMbOut, pricePerMbOut, includedRecords, pricePerRecord);
        }
    }

    record CreatePolicyRequest(@NotNull LocalDate startDate, LocalDate endDate, @NotNull @Valid TermsRequest terms,
                               @Size(max = 500) String reason) {
    }

    record AddVersionRequest(@NotNull @Valid TermsRequest terms, @Size(max = 500) String reason) {
    }

    record ChangeTermRequest(@NotNull LocalDate startDate, LocalDate endDate, @NotBlank @Size(max = 500) String reason) {
    }

    /**
     * @param periodStart nulo = fecha o próximo ciclo encerrado; início de um ciclo já fechado = nova revisão (exige
     *                    {@code reason})
     */
    record CloseRequest(LocalDate periodStart, @Size(max = 500) String reason) {
    }
}
