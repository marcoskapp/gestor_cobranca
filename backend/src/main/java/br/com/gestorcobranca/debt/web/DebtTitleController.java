package br.com.gestorcobranca.debt.web;

import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases.LedgerPage;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases.OwnershipView;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases.TitleBalanceView;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases.TitleSearch;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases.TitleView;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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
import java.util.Set;
import java.util.UUID;

/**
 * Títulos: consulta, saldo por componente, razão, titularidade e ações de ciclo de vida.
 * Lançamentos financeiros não têm endpoint aqui: entram pelos módulos payment/negotiation.
 */
@RestController
@RequestMapping(ApiPaths.V1)
class DebtTitleController {

    private final DebtTitleUseCases titles;

    DebtTitleController(DebtTitleUseCases titles) {
        this.titles = titles;
    }

    @GetMapping("/titles")
    @PreAuthorize("hasAuthority('DEBTOR_VIEW') or hasAuthority('CASE_VIEW')")
    PageResponse<TitleView> search(@RequestParam(required = false) UUID creditorId,
                                   @RequestParam(required = false) UUID portfolioId,
                                   @RequestParam(required = false) UUID debtorId,
                                   @RequestParam(required = false) String status,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueFrom,
                                   @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueTo,
                                   @RequestParam(required = false) Integer minOverdueDays,
                                   @RequestParam(required = false) Integer maxOverdueDays,
                                   @RequestParam(required = false) Integer page,
                                   @RequestParam(required = false) Integer size) {
        return titles.search(new TitleSearch(creditorId, portfolioId, debtorId, status, dueFrom, dueTo, minOverdueDays,
                maxOverdueDays), PageQuery.of(page, size));
    }

    @GetMapping("/debtors/{debtorId}/titles")
    @PreAuthorize("hasAuthority('DEBTOR_VIEW') or hasAuthority('CASE_VIEW')")
    List<TitleView> listByDebtor(@PathVariable UUID debtorId) {
        return titles.listByDebtor(debtorId);
    }

    @GetMapping("/titles/{id}")
    @PreAuthorize("hasAuthority('DEBTOR_VIEW') or hasAuthority('CASE_VIEW')")
    TitleView get(@PathVariable UUID id) {
        return titles.get(id);
    }

    @GetMapping("/titles/{id}/balance")
    @PreAuthorize("hasAuthority('DEBTOR_VIEW') or hasAuthority('CASE_VIEW')")
    TitleBalanceView balance(@PathVariable UUID id) {
        return titles.balance(id);
    }

    @GetMapping("/titles/{id}/ledger")
    @PreAuthorize("hasAuthority('DEBTOR_VIEW') or hasAuthority('CASE_VIEW')")
    LedgerPage ledger(@PathVariable UUID id, @RequestParam(required = false) Integer page,
                      @RequestParam(required = false) Integer size) {
        return titles.ledger(id, PageQuery.of(page, size));
    }

    @GetMapping("/titles/{id}/ownership")
    @PreAuthorize("hasAuthority('DEBTOR_VIEW') or hasAuthority('CASE_VIEW')")
    List<OwnershipView> ownership(@PathVariable UUID id) {
        return titles.ownershipHistory(id);
    }

    @PostMapping("/titles/{id}/suspend")
    @PreAuthorize("hasAuthority('DEBT_TITLE_MANAGE')")
    TitleView suspend(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
        return titles.suspend(id, request.reason());
    }

    @PostMapping("/titles/{id}/reactivate")
    @PreAuthorize("hasAuthority('DEBT_TITLE_MANAGE')")
    TitleView reactivate(@PathVariable UUID id) {
        return titles.reactivate(id);
    }

    @PostMapping("/titles/{id}/cancel")
    @PreAuthorize("hasAuthority('DEBT_TITLE_MANAGE')")
    TitleView cancel(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
        return titles.cancel(id, request.reason());
    }

    @PostMapping("/titles/return")
    @PreAuthorize("hasAuthority('DEBT_TITLE_MANAGE')")
    List<TitleView> returnToCreditor(@Valid @RequestBody ReturnRequest request) {
        return titles.returnToCreditor(request.titleIds(), request.reason());
    }

    @PostMapping("/titles/transfer")
    @PreAuthorize("hasAuthority('DEBT_TITLE_MANAGE')")
    List<TitleView> transfer(@Valid @RequestBody TransferRequest request) {
        return titles.transferPortfolio(request.titleIds(), request.targetPortfolioId(), request.reason());
    }

    record ReasonRequest(@NotBlank @Size(max = 500) String reason) {
    }

    record ReturnRequest(@NotEmpty @Size(max = 500) Set<@NotNull UUID> titleIds, @NotBlank @Size(max = 500) String reason) {
    }

    record TransferRequest(@NotEmpty @Size(max = 500) Set<@NotNull UUID> titleIds, @NotNull UUID targetPortfolioId,
                           @NotBlank @Size(max = 500) String reason) {
    }
}
