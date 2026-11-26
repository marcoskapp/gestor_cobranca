package br.com.gestorcobranca.debt.web;

import br.com.gestorcobranca.debt.application.port.in.ReversalHoldUseCases;
import br.com.gestorcobranca.debt.application.port.in.ReversalHoldUseCases.HeldTitle;
import br.com.gestorcobranca.shared.web.ApiPaths;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Selo "Estorno em conferência" nos títulos do devedor (H07 parte 3): só os títulos retidos dos credores no escopo,
 * para quem vê títulos ({@code DEBTOR_VIEW} ou {@code CASE_VIEW}); o pagamento só com {@code PAYMENT_VIEW}.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/titles/reversal-holds")
class ReversalHoldTitlesController {

    private final ReversalHoldUseCases holds;

    ReversalHoldTitlesController(ReversalHoldUseCases holds) {
        this.holds = holds;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('DEBTOR_VIEW', 'CASE_VIEW')")
    List<HeldTitle> ofDebtor(@RequestParam UUID debtorId) {
        return holds.heldTitlesOfDebtor(debtorId);
    }
}
