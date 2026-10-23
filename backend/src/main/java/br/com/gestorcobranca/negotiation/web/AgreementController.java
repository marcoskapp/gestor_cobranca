package br.com.gestorcobranca.negotiation.web;

import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.AgreementFilter;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.AgreementView;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.PaymentView;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.web.ApiPaths;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Acordos: consulta, parcelas, pagamentos aplicados, cancelamento e termo. */
@RestController
@RequestMapping(ApiPaths.V1 + "/negotiation/agreements")
@Tag(name = "Negotiation — acordos", description = "Acordos, parcelas, pagamentos, cancelamento e termo")
class AgreementController {

    private final AgreementUseCases agreements;

    AgreementController(AgreementUseCases agreements) {
        this.agreements = agreements;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('AGREEMENT_VIEW')")
    PageResponse<AgreementView> search(@RequestParam(required = false) UUID creditorId,
                                       @RequestParam(required = false) UUID debtorId,
                                       @RequestParam(required = false) UUID caseId,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) Integer page,
                                       @RequestParam(required = false) Integer size) {
        return agreements.search(new AgreementFilter(creditorId, debtorId, caseId, status), PageQuery.of(page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('AGREEMENT_VIEW')")
    AgreementView get(@PathVariable UUID id) {
        return agreements.get(id);
    }

    @GetMapping("/{id}/installments")
    @PreAuthorize("hasAuthority('AGREEMENT_VIEW')")
    List<AgreementUseCases.InstallmentView> installments(@PathVariable UUID id) {
        return agreements.get(id).installments();
    }

    @GetMapping("/{id}/payments")
    @PreAuthorize("hasAuthority('AGREEMENT_VIEW')")
    List<PaymentView> payments(@PathVariable UUID id) {
        return agreements.payments(id);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('AGREEMENT_CANCEL')")
    AgreementView cancel(@PathVariable UUID id, @Valid @RequestBody CancelRequest r) {
        return agreements.cancel(id, r.reason());
    }

    @GetMapping("/{id}/term")
    @PreAuthorize("hasAuthority('AGREEMENT_VIEW')")
    ResponseEntity<InputStreamResource> term(@PathVariable UUID id) {
        return TermResponses.of(agreements.openTerm(id));
    }

    record CancelRequest(@NotBlank @Size(max = 500) String reason) {
    }
}
