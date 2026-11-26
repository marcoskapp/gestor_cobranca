package br.com.gestorcobranca.payment.web;

import br.com.gestorcobranca.debt.application.port.in.ReversalHoldUseCases.HoldFilter;
import br.com.gestorcobranca.debt.application.port.in.ReversalHoldUseCases.ReleaseCommand;
import br.com.gestorcobranca.payment.application.port.in.PaymentReversalHoldsUseCases;
import br.com.gestorcobranca.payment.application.port.in.PaymentReversalHoldsUseCases.HeldReversalItem;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.web.ApiPaths;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Aba "Estornos em conferência" de "Baixas informadas pelo credor" (H07 parte 3). Sem exportação. */
@RestController
@RequestMapping(ApiPaths.V1 + "/payment/reversal-holds")
class PaymentReversalHoldsController {

    private final PaymentReversalHoldsUseCases holds;

    PaymentReversalHoldsController(PaymentReversalHoldsUseCases holds) {
        this.holds = holds;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PAYMENT_VIEW')")
    PageResponse<HeldReversalItem> search(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID creditorId,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) UUID paymentId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return holds.search(new HoldFilter(from, to, creditorId, state, paymentId), PageQuery.of(page, size));
    }

    @GetMapping("/{holdId}")
    @PreAuthorize("hasAuthority('PAYMENT_VIEW')")
    HeldReversalItem get(@PathVariable UUID holdId) {
        return holds.get(holdId);
    }

    @GetMapping("/by-payment/{paymentId}")
    @PreAuthorize("hasAuthority('PAYMENT_VIEW')")
    List<HeldReversalItem> forPayment(@PathVariable UUID paymentId) {
        return holds.forPayment(paymentId);
    }

    @PostMapping("/{holdId}/release")
    @PreAuthorize("hasAuthority('PAYMENT_REVIEW')")
    HeldReversalItem release(@PathVariable UUID holdId, @RequestBody ReleaseRequest request) {
        return holds.release(holdId, new ReleaseCommand(request.version() == null ? -1 : request.version(),
                request.reasonCode(), request.note(), Boolean.TRUE.equals(request.confirmedWithCreditor())));
    }

    /** @param version versão da retenção lida na tela (conflito se mudou) */
    record ReleaseRequest(Long version, String reasonCode, String note, Boolean confirmedWithCreditor) {
    }
}
