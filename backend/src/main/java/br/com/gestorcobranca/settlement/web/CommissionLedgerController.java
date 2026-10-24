package br.com.gestorcobranca.settlement.web;

import br.com.gestorcobranca.settlement.application.port.in.CommissionLedgerQueries;
import br.com.gestorcobranca.settlement.application.port.in.CommissionLedgerQueries.ReceiptDetailView;
import br.com.gestorcobranca.settlement.application.port.in.CommissionLedgerQueries.ReceiptFilter;
import br.com.gestorcobranca.settlement.application.port.in.CommissionLedgerQueries.ReceiptView;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.web.ApiPaths;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/** Recebimentos e razão de comissão da assessoria (com memória de cálculo). */
@RestController
@RequestMapping(ApiPaths.V1 + "/settlement/receipts")
class CommissionLedgerController {

    private final CommissionLedgerQueries ledger;

    CommissionLedgerController(CommissionLedgerQueries ledger) {
        this.ledger = ledger;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('COMMISSION_VIEW', 'SETTLEMENT_VIEW')")
    PageResponse<ReceiptView> search(@RequestParam(required = false) UUID creditorId,
                                     @RequestParam(required = false) UUID agreementId,
                                     @RequestParam(required = false) LocalDate from,
                                     @RequestParam(required = false) LocalDate to,
                                     @RequestParam(required = false) String ratingStatus,
                                     @RequestParam(required = false) Boolean inStatement,
                                     @RequestParam(required = false) Integer page,
                                     @RequestParam(required = false) Integer size) {
        return ledger.receipts(new ReceiptFilter(creditorId, agreementId, from, to, ratingStatus, inStatement),
                PageQuery.of(page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('COMMISSION_VIEW', 'SETTLEMENT_VIEW')")
    ReceiptDetailView get(@PathVariable UUID id) {
        return ledger.receipt(id);
    }

    /** Reapura os recebimentos do credor aguardando regra contratual. */
    @PostMapping("/rate-pending")
    @PreAuthorize("hasAuthority('COMMISSION_MANAGE')")
    Map<String, Integer> ratePending(@RequestParam UUID creditorId) {
        return Map.of("rated", ledger.ratePending(creditorId));
    }
}
