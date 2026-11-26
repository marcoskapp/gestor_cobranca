package br.com.gestorcobranca.debt.web;

import br.com.gestorcobranca.debt.application.port.in.CreditorReportedTitlesQueries;
import br.com.gestorcobranca.debt.application.port.in.CreditorReportedTitlesQueries.ReportedTitleFilter;
import br.com.gestorcobranca.debt.application.port.in.CreditorReportedTitlesQueries.ReportedTitlesPage;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.web.ApiPaths;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/** "Baixas informadas pelo credor", aba "Pela carga do credor" (H07 parte 2b). Somente leitura, sem exportação. */
@RestController
@RequestMapping(ApiPaths.V1 + "/titles/creditor-reported")
class CreditorReportedTitlesController {

    private final CreditorReportedTitlesQueries settlements;

    CreditorReportedTitlesController(CreditorReportedTitlesQueries settlements) {
        this.settlements = settlements;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAYMENT_VIEW')")
    ReportedTitlesPage search(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID creditorId,
            @RequestParam(required = false) UUID importBatchId,
            @RequestParam(required = false) String situation,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort) {
        return settlements.search(new ReportedTitleFilter(from, to, creditorId, importBatchId, situation),
                PageQuery.of(page, size, sort));
    }
}
