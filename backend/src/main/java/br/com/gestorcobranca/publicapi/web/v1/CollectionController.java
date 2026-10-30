package br.com.gestorcobranca.publicapi.web.v1;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.DisputeView;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.OpenDisputeCommand;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.AgreementView;
import br.com.gestorcobranca.publicapi.web.security.UsageRecords;
import br.com.gestorcobranca.publicapi.web.v1.PublicApiModels.AgreementDto;
import br.com.gestorcobranca.publicapi.web.v1.PublicApiModels.CaseDto;
import br.com.gestorcobranca.publicapi.web.v1.PublicApiModels.DisputeDto;
import br.com.gestorcobranca.publicapi.web.v1.PublicApiSupport.ListResponse;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.web.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
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
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * Situação da cobrança (somente leitura): casos e acordos com parcelas; contestações (consulta e abertura em nome do
 * credor, pelo caso de uso do collection — vincula os títulos e suspende a cobrança).
 */
@RestController
@Validated
@RequestMapping(ApiPaths.PUBLIC_API_V1)
@Tag(name = "Cobrança", description = "Casos, acordos e contestações")
class CollectionController {

    private final CaseUseCases cases;
    private final AgreementUseCases agreements;
    private final DisputeUseCases disputes;
    private final PublicApiSupport support;

    CollectionController(CaseUseCases cases, AgreementUseCases agreements, DisputeUseCases disputes,
                         PublicApiSupport support) {
        this.cases = cases;
        this.agreements = agreements;
        this.disputes = disputes;
        this.support = support;
    }

    @GetMapping("/collection-cases")
    @PreAuthorize("hasAuthority('SCOPE_collection:read')")
    @Operation(summary = "Casos de cobrança (sincronização incremental)")
    ListResponse<CaseDto> cases(@RequestParam(required = false) UUID portfolioId,
                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant updatedSince,
                                @RequestParam(required = false) String cursor,
                                @RequestParam(required = false) @Min(1) @Max(500) Integer limit) {
        return support.list(cases.changes(support.creditorId(), portfolioId, support.query(updatedSince, cursor, limit)),
                CaseDto::of);
    }

    @GetMapping("/collection-cases/{id}")
    @PreAuthorize("hasAuthority('SCOPE_collection:read')")
    @Operation(summary = "Caso de cobrança")
    CaseDto collectionCase(@PathVariable UUID id) {
        return CaseDto.of(ownCase(id));
    }

    @GetMapping("/agreements")
    @PreAuthorize("hasAuthority('SCOPE_agreements:read')")
    @Operation(summary = "Acordos (sincronização incremental)", description = "Com parcelas e situação de cada uma.")
    ListResponse<AgreementDto> agreements(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant updatedSince,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) @Min(1) @Max(500) Integer limit) {
        return support.list(agreements.changes(support.creditorId(), support.query(updatedSince, cursor, limit)),
                AgreementDto::of);
    }

    @GetMapping("/agreements/{id}")
    @PreAuthorize("hasAuthority('SCOPE_agreements:read')")
    @Operation(summary = "Acordo")
    AgreementDto agreement(@PathVariable UUID id) {
        AgreementView view = agreements.get(id);
        if (!view.creditorId().equals(support.creditorId())) {
            throw ResourceNotFoundException.of("agreement", id);
        }
        return AgreementDto.of(view);
    }

    @GetMapping("/disputes")
    @PreAuthorize("hasAuthority('SCOPE_disputes:read')")
    @Operation(summary = "Contestações (sincronização incremental)")
    ListResponse<DisputeDto> disputes(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant updatedSince,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) @Min(1) @Max(500) Integer limit) {
        return support.list(disputes.changes(support.creditorId(), support.query(updatedSince, cursor, limit)),
                DisputeDto::of);
    }

    @GetMapping("/disputes/{id}")
    @PreAuthorize("hasAuthority('SCOPE_disputes:read')")
    @Operation(summary = "Contestação")
    DisputeDto dispute(@PathVariable UUID id) {
        DisputeView view = disputes.get(id);
        if (!view.creditorId().equals(support.creditorId())) {
            throw ResourceNotFoundException.of("dispute", id);
        }
        return DisputeDto.of(view);
    }

    @PostMapping("/disputes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('SCOPE_disputes:write')")
    @Operation(summary = "Abrir contestação em nome do credor", description = "Origem CREDITOR. Por padrão bloqueia a "
            + "cobrança dos títulos contestados até a resolução.")
    DisputeDto openDispute(@Valid @RequestBody OpenDisputeRequest r, HttpServletRequest http) {
        ownCase(r.caseId());
        DisputeDto dto = DisputeDto.of(disputes.open(r.caseId(), new OpenDisputeCommand(r.titleIds(), r.type(),
                "CREDITOR", r.description(), r.blocksCollection(), r.responseDueDate())));
        UsageRecords.add(http, 1);
        return dto;
    }

    private CaseView ownCase(UUID id) {
        CaseView view = cases.get(id);
        if (!view.creditorId().equals(support.creditorId())) {
            throw ResourceNotFoundException.of("collection_case", id);
        }
        return view;
    }

    record OpenDisputeRequest(@NotNull UUID caseId, @Size(max = 500) Set<UUID> titleIds,
                              @NotBlank @Pattern(regexp = "NOT_RECOGNIZED|ALREADY_PAID|WRONG_AMOUNT|FRAUD|PRESCRIBED|"
                                      + "PRODUCT_OR_SERVICE|OTHER") String type,
                              @NotBlank @Size(max = 2000) String description, Boolean blocksCollection,
                              LocalDate responseDueDate) {
    }
}
