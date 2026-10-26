package br.com.gestorcobranca.payment.web;

import br.com.gestorcobranca.payment.application.port.in.PaymentReviewStatusUseCases;
import br.com.gestorcobranca.payment.application.port.in.PaymentReviewStatusUseCases.AgreementReviewBadge;
import br.com.gestorcobranca.payment.application.port.in.PaymentReviewStatusUseCases.AgreementReviewStatus;
import br.com.gestorcobranca.payment.application.port.in.PaymentReviewStatusUseCases.DebtorReviewStatus;
import br.com.gestorcobranca.shared.web.ApiPaths;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Situação de revisão dos pagamentos externos para quem acompanha acordos e casos ({@code AGREEMENT_VIEW}):
 * pendentes de aprovação e recusados recentes, sem evidência nem observação.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/payment/review-status")
class PaymentReviewStatusController {

    private final PaymentReviewStatusUseCases reviewStatus;

    PaymentReviewStatusController(PaymentReviewStatusUseCases reviewStatus) {
        this.reviewStatus = reviewStatus;
    }

    @GetMapping("/agreements/{agreementId}")
    @PreAuthorize("hasAuthority('AGREEMENT_VIEW')")
    AgreementReviewStatus agreement(@PathVariable UUID agreementId) {
        return reviewStatus.forAgreement(agreementId);
    }

    /** @param ids acordos da página (separados por vírgula ou repetidos), até 200 */
    @GetMapping("/agreements")
    @PreAuthorize("hasAuthority('AGREEMENT_VIEW')")
    List<AgreementReviewBadge> agreements(@RequestParam List<UUID> ids) {
        return reviewStatus.forAgreements(ids);
    }

    @GetMapping("/debtors/{debtorId}")
    @PreAuthorize("hasAuthority('AGREEMENT_VIEW')")
    DebtorReviewStatus debtor(@PathVariable UUID debtorId, @RequestParam UUID creditorId) {
        return reviewStatus.forDebtor(creditorId, debtorId);
    }
}
