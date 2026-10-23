package br.com.gestorcobranca.debt.web;

import br.com.gestorcobranca.debt.application.port.in.DebtorSheetQueries;
import br.com.gestorcobranca.debt.application.port.in.DebtorSheetQueries.DebtorSummary;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases.DebtorName;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases.DebtorView;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases.RegisterDebtorCommand;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases.RevealedDocument;
import br.com.gestorcobranca.debt.application.port.in.DebtorUseCases.UpdateDebtorCommand;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
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

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Devedores. A busca por documento usa POST para que o CPF/CNPJ não trafegue na URL (que fica
 * em logs de proxy/ALB e no histórico do navegador). A anonimização não tem endpoint aqui: é
 * acionada pelo módulo compliance no atendimento de solicitações de titulares.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/debtors")
class DebtorController {

    private final DebtorUseCases debtors;
    private final DebtorSheetQueries sheets;

    DebtorController(DebtorUseCases debtors, DebtorSheetQueries sheets) {
        this.debtors = debtors;
        this.sheets = sheets;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('DEBTOR_VIEW')")
    PageResponse<DebtorView> search(@RequestParam(required = false) String q,
                                    @RequestParam(required = false) UUID creditorId,
                                    @RequestParam(required = false) UUID portfolioId,
                                    @RequestParam(required = false) Integer page,
                                    @RequestParam(required = false) Integer size) {
        return debtors.search(q, creditorId, portfolioId, PageQuery.of(page, size));
    }

    @PostMapping("/lookup")
    @PreAuthorize("hasAuthority('DEBTOR_VIEW')")
    DebtorView findByDocument(@Valid @RequestBody DocumentLookupRequest request) {
        return debtors.findByDocument(request.document())
                .orElseThrow(() -> new ResourceNotFoundException("debtor", "document"));
    }

    /**
     * Nomes de exibição para telas operacionais (caixa de entrada, estação, listagens), sem
     * documento nem contatos: até {@value DebtorUseCases#MAX_NAMES} ids; os fora do escopo de
     * credores do ator são omitidos.
     *
     * @param ids ids separados por vírgula ou repetidos ({@code ?ids=a&ids=b})
     */
    @GetMapping("/names")
    @PreAuthorize("hasAuthority('DEBTOR_VIEW') or hasAuthority('CASE_VIEW') or hasAuthority('INBOX_VIEW')")
    List<DebtorName> names(@RequestParam List<UUID> ids) {
        return debtors.names(ids);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('DEBTOR_VIEW')")
    DebtorView get(@PathVariable UUID id) {
        return debtors.get(id);
    }

    @GetMapping("/{id}/summary")
    @PreAuthorize("hasAuthority('DEBTOR_VIEW') or hasAuthority('CASE_VIEW')")
    DebtorSummary summary(@PathVariable UUID id) {
        return sheets.summary(id);
    }

    @GetMapping("/{id}/document")
    @PreAuthorize("hasAuthority('DEBTOR_SENSITIVE_VIEW')")
    RevealedDocument revealDocument(@PathVariable UUID id) {
        return debtors.revealDocument(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('DEBTOR_MANAGE')")
    DebtorView register(@Valid @RequestBody RegisterDebtorRequest request) {
        return debtors.register(new RegisterDebtorCommand(request.document(), request.name(), request.birthDate(),
                request.notes()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('DEBTOR_MANAGE')")
    DebtorView update(@PathVariable UUID id, @Valid @RequestBody UpdateDebtorRequest request) {
        return debtors.update(id, new UpdateDebtorCommand(request.name(), request.birthDate(), request.notes()));
    }

    record DocumentLookupRequest(@NotBlank @Size(max = 20) String document) {
    }

    record RegisterDebtorRequest(@NotBlank @Size(max = 20) String document, @NotBlank @Size(max = 200) String name,
                                 @Past LocalDate birthDate, @Size(max = 2000) String notes) {
    }

    record UpdateDebtorRequest(@NotBlank @Size(max = 200) String name, @Past LocalDate birthDate,
                               @Size(max = 2000) String notes) {
    }
}
