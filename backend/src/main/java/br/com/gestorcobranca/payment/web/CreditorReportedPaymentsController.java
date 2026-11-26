package br.com.gestorcobranca.payment.web;

import br.com.gestorcobranca.payment.application.port.in.CreditorReportedPaymentsUseCases;
import br.com.gestorcobranca.payment.application.port.in.CreditorReportedPaymentsUseCases.ReportedPaymentFilter;
import br.com.gestorcobranca.payment.application.port.in.CreditorReportedPaymentsUseCases.ReportedPaymentsPage;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.web.ApiPaths;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** "Baixas informadas pelo credor", aba dos pagamentos (H07 parte 2b). Somente leitura, sem exportação. */
@RestController
@RequestMapping(ApiPaths.V1 + "/payment/creditor-reported")
class CreditorReportedPaymentsController {

    private final CreditorReportedPaymentsUseCases settlements;

    CreditorReportedPaymentsController(CreditorReportedPaymentsUseCases settlements) {
        this.settlements = settlements;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAYMENT_VIEW')")
    ReportedPaymentsPage search(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID creditorId,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) UUID tokenId,
            @RequestParam(required = false) BigDecimal minAmount,
            @RequestParam(required = false) BigDecimal maxAmount,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return settlements.search(new ReportedPaymentFilter(from, to, creditorId, channel, tokenId, minAmount,
                maxAmount, status), PageQuery.of(page, size, sort));
    }
}
