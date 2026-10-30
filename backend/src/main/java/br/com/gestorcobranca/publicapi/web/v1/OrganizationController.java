package br.com.gestorcobranca.publicapi.web.v1;

import br.com.gestorcobranca.organization.application.port.in.CreditorUseCases;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases.CreatePortfolioCommand;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases.UpdatePortfolioCommand;
import br.com.gestorcobranca.organization.application.port.in.ServiceContractUseCases;
import br.com.gestorcobranca.publicapi.web.security.UsageRecords;
import br.com.gestorcobranca.publicapi.web.v1.PublicApiModels.ContractDto;
import br.com.gestorcobranca.publicapi.web.v1.PublicApiModels.CreditorDto;
import br.com.gestorcobranca.publicapi.web.v1.PublicApiModels.PortfolioDto;
import br.com.gestorcobranca.publicapi.web.v1.PublicApiSupport.ListResponse;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.web.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Credor do token, contratos e carteiras: as regras de carteira são as do módulo organization (ciclo de vida, RB02). */
@RestController
@Validated
@RequestMapping(ApiPaths.PUBLIC_API_V1)
@Tag(name = "Carteiras", description = "Credor do token, contratos de serviço e carteiras")
class OrganizationController {

    private final CreditorUseCases creditors;
    private final ServiceContractUseCases contracts;
    private final PortfolioUseCases portfolios;
    private final PublicApiSupport support;

    OrganizationController(CreditorUseCases creditors, ServiceContractUseCases contracts, PortfolioUseCases portfolios,
                           PublicApiSupport support) {
        this.creditors = creditors;
        this.contracts = contracts;
        this.portfolios = portfolios;
        this.support = support;
    }

    @GetMapping("/creditor")
    @PreAuthorize("hasAuthority('SCOPE_portfolios:read')")
    @Operation(summary = "Credor do token", description = "Dados cadastrais do credor ao qual o token pertence.")
    CreditorDto creditor() {
        return CreditorDto.of(creditors.get(support.creditorId()));
    }

    @GetMapping("/contracts")
    @PreAuthorize("hasAuthority('SCOPE_portfolios:read')")
    @Operation(summary = "Contratos de serviço do credor", description = "Necessários para criar carteiras.")
    List<ContractDto> contracts() {
        return contracts.listByCreditor(support.creditorId()).stream().map(ContractDto::of).toList();
    }

    @GetMapping("/portfolios")
    @PreAuthorize("hasAuthority('SCOPE_portfolios:read')")
    @Operation(summary = "Carteiras (sincronização incremental)", description = "Ordem crescente de atualização; use "
            + "`nextCursor` para continuar e `updatedSince` para buscar só o que mudou.")
    ListResponse<PortfolioDto> listPortfolios(
            @Parameter(description = "Só alterados a partir deste instante (ISO-8601)")
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant updatedSince,
            @Parameter(description = "Cursor devolvido na página anterior") @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Min(1) @Max(500) Integer limit) {
        return support.list(portfolios.changes(support.creditorId(), support.query(updatedSince, cursor, limit)),
                PortfolioDto::of);
    }

    @GetMapping("/portfolios/{id}")
    @PreAuthorize("hasAuthority('SCOPE_portfolios:read')")
    @Operation(summary = "Carteira", description = "Resposta com `ETag` para alterações com `If-Match`.")
    ResponseEntity<PortfolioDto> getPortfolio(@PathVariable UUID id) {
        PortfolioDto dto = PortfolioDto.of(ownPortfolio(id));
        return PublicApiSupport.withEtag(dto, dto.version());
    }

    @PostMapping("/portfolios")
    @PreAuthorize("hasAuthority('SCOPE_portfolios:write')")
    @Operation(summary = "Criar carteira", description = "Nasce em rascunho (DRAFT); ative com `POST /portfolios/{id}/status`.")
    ResponseEntity<PortfolioDto> createPortfolio(@Valid @RequestBody CreatePortfolioRequest r, HttpServletRequest http) {
        PortfolioDto dto = PortfolioDto.of(portfolios.create(new CreatePortfolioCommand(support.creditorId(),
                r.contractId(), r.code(), r.name(), r.description(), r.receivedOn(), r.validUntil())));
        UsageRecords.add(http, 1);
        return ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.ETAG, PublicApiSupport.etag(dto.version()))
                .body(dto);
    }

    @PutMapping("/portfolios/{id}")
    @PreAuthorize("hasAuthority('SCOPE_portfolios:write')")
    @Operation(summary = "Atualizar carteira", description = "Envie `If-Match` com o ETag lido para evitar sobrescrever "
            + "alterações concorrentes (412 se mudou).")
    ResponseEntity<PortfolioDto> updatePortfolio(@PathVariable UUID id,
                                                 @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                                 @Valid @RequestBody UpdatePortfolioRequest r, HttpServletRequest http) {
        ownPortfolio(id);
        PortfolioDto dto = PortfolioDto.of(portfolios.update(id, new UpdatePortfolioCommand(r.name(), r.description(),
                r.receivedOn(), r.validUntil()), PublicApiSupport.ifMatch(ifMatch)));
        UsageRecords.add(http, 1);
        return PublicApiSupport.withEtag(dto, dto.version());
    }

    @PostMapping("/portfolios/{id}/status")
    @PreAuthorize("hasAuthority('SCOPE_portfolios:write')")
    @Operation(summary = "Mudar situação da carteira", description = "Ativar, suspender, devolver ou encerrar, conforme "
            + "o ciclo de vida (DRAFT→ACTIVE|CLOSED; ACTIVE→SUSPENDED|RETURNED|CLOSED; SUSPENDED→ACTIVE|RETURNED|CLOSED; "
            + "RETURNED→CLOSED). Suspensão e devolução exigem motivo.")
    ResponseEntity<PortfolioDto> changeStatus(@PathVariable UUID id,
                                              @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
                                              @Valid @RequestBody ChangeStatusRequest r, HttpServletRequest http) {
        ownPortfolio(id);
        PortfolioDto dto = PortfolioDto.of(portfolios.changeStatus(id, r.status(), r.reason(),
                PublicApiSupport.ifMatch(ifMatch)));
        UsageRecords.add(http, 1);
        return PublicApiSupport.withEtag(dto, dto.version());
    }

    /** Carteira do credor do token; de outro credor = inexistente (404, sem revelar). */
    private PortfolioUseCases.PortfolioView ownPortfolio(UUID id) {
        var view = portfolios.get(id);
        if (!view.creditorId().equals(support.creditorId())) {
            throw ResourceNotFoundException.of("portfolio", id);
        }
        return view;
    }

    record CreatePortfolioRequest(@NotNull UUID contractId,
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
