package br.com.gestorcobranca.publicapi.web.admin;

import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases.PolicyView;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases.PositionView;
import br.com.gestorcobranca.publicapi.application.port.in.PlatformBillingUseCases.StatementView;
import br.com.gestorcobranca.publicapi.application.port.in.TenantPlatformBillingUseCases;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.YearMonth;
import java.util.UUID;

/** A cobrança da plataforma à assessoria pelo consumo da API, vista pela própria assessoria (somente leitura). */
@RestController
@RequestMapping(ApiPaths.V1 + "/public-api/platform-billing")
@PreAuthorize("hasAuthority('TENANT_BILLING_VIEW')")
class TenantPlatformBillingController {

    private final TenantPlatformBillingUseCases billing;

    TenantPlatformBillingController(TenantPlatformBillingUseCases billing) {
        this.billing = billing;
    }

    @GetMapping("/policy")
    PolicyView policy() {
        return billing.policy();
    }

    @GetMapping("/position")
    PositionView position() {
        return billing.position();
    }

    @GetMapping("/statements")
    PageResponse<StatementView> statements(@RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month,
                                           @RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer size) {
        return billing.statements(month, PageQuery.of(page, size));
    }

    @GetMapping("/statements/{id}")
    StatementView statement(@PathVariable UUID id) {
        return billing.statement(id);
    }

    @GetMapping("/statements/{id}/export")
    @PreAuthorize("hasAuthority('TENANT_BILLING_VIEW') and hasAuthority('REPORT_EXPORT')")
    void export(@PathVariable UUID id, HttpServletResponse response) throws IOException {
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename("extrato-plataforma-%s.csv".formatted(id)).build().toString());
        billing.exportStatement(id, response.getOutputStream());
    }
}
