package br.com.gestorcobranca.organization.web;

import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases.CreatePortfolioCommand;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases.PortfolioView;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases.UpdatePortfolioCommand;
import br.com.gestorcobranca.shared.web.ApiPaths;
import br.com.gestorcobranca.shared.web.PageQuery;
import br.com.gestorcobranca.shared.web.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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
import java.util.UUID;

@RestController
@RequestMapping(ApiPaths.V1 + "/portfolios")
class PortfolioController {

    private final PortfolioUseCases portfolios;

    PortfolioController(PortfolioUseCases portfolios) {
        this.portfolios = portfolios;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PORTFOLIO_VIEW')")
    PageResponse<PortfolioView> search(@RequestParam(required = false) UUID creditorId,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) String q,
                                       @RequestParam(required = false) Integer page,
                                       @RequestParam(required = false) Integer size) {
        return portfolios.search(creditorId, status, q, PageQuery.of(page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PORTFOLIO_VIEW')")
    PortfolioView get(@PathVariable UUID id) {
        return portfolios.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PORTFOLIO_MANAGE')")
    PortfolioView create(@Valid @RequestBody CreatePortfolioRequest request) {
        return portfolios.create(new CreatePortfolioCommand(request.creditorId(), request.contractId(), request.code(),
                request.name(), request.description(), request.receivedOn(), request.validUntil()));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PORTFOLIO_MANAGE')")
    PortfolioView update(@PathVariable UUID id, @Valid @RequestBody UpdatePortfolioRequest request) {
        return portfolios.update(id, new UpdatePortfolioCommand(request.name(), request.description(),
                request.receivedOn(), request.validUntil()));
    }

    @PostMapping("/{id}/status")
    @PreAuthorize("hasAuthority('PORTFOLIO_MANAGE')")
    PortfolioView changeStatus(@PathVariable UUID id, @Valid @RequestBody ChangeStatusRequest request) {
        return portfolios.changeStatus(id, request.status(), request.reason());
    }

    record CreatePortfolioRequest(@NotNull UUID creditorId, @NotNull UUID contractId,
                                  @NotBlank @Size(max = 40) @Pattern(regexp = "[A-Za-z0-9_-]+") String code,
                                  @NotBlank @Size(max = 150) String name, @Size(max = 1000) String description,
                                  @NotNull LocalDate receivedOn, LocalDate validUntil) {
    }

    record UpdatePortfolioRequest(@NotBlank @Size(max = 150) String name, @Size(max = 1000) String description,
                                  @NotNull LocalDate receivedOn, LocalDate validUntil) {
    }

    record ChangeStatusRequest(@NotBlank @Pattern(regexp = "ACTIVE|SUSPENDED|RETURNED|CLOSED") String status,
                               @Size(max = 500) String reason) {
    }
}
