package br.com.gestorcobranca.publicapi.web.v1;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.DisputeView;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.CreditorResponseCommand;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.EvidenceUpload;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases.OpenDisputeCommand;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.AgreementView;
import br.com.gestorcobranca.publicapi.web.security.UsageRecords;
import br.com.gestorcobranca.publicapi.web.v1.PublicApiModels.AgreementDto;
import br.com.gestorcobranca.publicapi.web.v1.PublicApiModels.CaseDto;
import br.com.gestorcobranca.publicapi.web.v1.PublicApiModels.CreditorRequestDto;
import br.com.gestorcobranca.publicapi.web.v1.PublicApiModels.CreditorResponseDto;
import br.com.gestorcobranca.publicapi.web.v1.PublicApiModels.DisputeDto;
import br.com.gestorcobranca.publicapi.web.v1.PublicApiSupport.ListResponse;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.web.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * Situação da cobrança (somente leitura): casos e acordos com parcelas; contestações (consulta e abertura em nome do
 * credor, pelo caso de uso do collection — vincula os títulos e suspende a cobrança; pedidos da assessoria ao credor
 * e resposta do credor).
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
    private final DocumentUploadLimiter uploadLimiter;

    CollectionController(CaseUseCases cases, AgreementUseCases agreements, DisputeUseCases disputes,
                         PublicApiSupport support, DocumentUploadLimiter uploadLimiter) {
        this.cases = cases;
        this.agreements = agreements;
        this.disputes = disputes;
        this.support = support;
        this.uploadLimiter = uploadLimiter;
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
        return DisputeDto.of(ownDispute(id));
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

    @GetMapping("/disputes/{id}/creditor-requests")
    @PreAuthorize("hasAuthority('SCOPE_disputes:read')")
    @Operation(summary = "Pedidos da assessoria ao credor na contestação",
            description = "Pedidos formais de documentos/posição enviados aos contatos de contestação do credor, com prazo "
                    + "pedido e situação (enviado, respondido, vencido).")
    java.util.List<CreditorRequestDto> creditorRequests(@PathVariable UUID id) {
        ownDispute(id);
        return disputes.creditorRequests(id).stream().map(CreditorRequestDto::of).toList();
    }

    @GetMapping("/disputes/{id}/creditor-responses")
    @PreAuthorize("hasAuthority('SCOPE_disputes:read')")
    @Operation(summary = "Respostas do credor na contestação",
            description = "Inclui a situação dos documentos enviados pela API: em verificação de segurança, aceitos ou "
                    + "recusados.")
    java.util.List<CreditorResponseDto> creditorResponses(@PathVariable UUID id) {
        ownDispute(id);
        return disputes.creditorResponses(id).stream().map(CreditorResponseDto::of).toList();
    }

    @PostMapping(path = "/disputes/{id}/creditor-responses", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('SCOPE_disputes:write')")
    @Operation(summary = "Responder ao pedido da assessoria na contestação",
            description = "Posição do credor (CONFIRMS_DEBT, ACCEPTS_CLAIM, PARTIAL ou INFORMATION) e resumo. Sem requestId, "
                    + "responde ao pedido mais recente aguardando retorno. A análise e a resolução continuam com a "
                    + "assessoria. Para enviar documentos, use a mesma rota em multipart/form-data.")
    CreditorResponseDto respond(@PathVariable UUID id, @Valid @RequestBody CreditorResponseRequest r,
                                HttpServletRequest http) {
        ownDispute(id);
        CreditorResponseDto dto = CreditorResponseDto.of(disputes.registerCreditorResponse(id,
                new CreditorResponseCommand(r.position(), r.note(), r.requestId(), null), java.util.List.of()));
        UsageRecords.add(http, 1);
        return dto;
    }

    @PostMapping(path = "/disputes/{id}/creditor-responses", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('SCOPE_disputes:write')")
    @Operation(summary = "Responder ao pedido da assessoria na contestação, com documentos",
            description = "Partes: `response` (JSON, mesmo conteúdo da resposta sem documentos) e `files` (até 5 arquivos "
                    + "PDF, PNG ou JPEG de até 10 MB cada; o tipo é conferido pelo conteúdo, não pela extensão). Os "
                    + "documentos passam por verificação de segurança (quarentena) antes de chegar à assessoria: "
                    + "acompanhe por `GET /disputes/{id}/creditor-responses` (`documentsInVerification`, "
                    + "`documentsAccepted`, `documentsRejected`). Limite próprio de envios com documentos por token "
                    + "(429 `rate_limited` com `Retry-After`).")
    CreditorResponseDto respondWithDocuments(@PathVariable UUID id,
                                             @Valid @RequestPart("response") CreditorResponseRequest r,
                                             @RequestPart(name = "files", required = false) java.util.List<MultipartFile> files,
                                             HttpServletRequest http, HttpServletResponse httpResponse) {
        ownDispute(id);
        java.util.List<MultipartFile> parts = files == null ? java.util.List.of()
                : files.stream().filter(f -> f != null && !f.isEmpty()).toList();
        if (!parts.isEmpty()) {
            uploadLimiter.acquire(support.tokenId(), "dispute_creditor_response", httpResponse);
        }
        java.util.List<EvidenceUpload> uploads = new java.util.ArrayList<>();
        for (MultipartFile f : parts) {
            try {
                uploads.add(new EvidenceUpload(f.getOriginalFilename(), f.getContentType(), f.getSize(), f.getInputStream()));
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException("Falha ao ler o arquivo enviado", e);
            }
        }
        CreditorResponseDto dto = CreditorResponseDto.of(disputes.registerCreditorResponse(id,
                new CreditorResponseCommand(r.position(), r.note(), r.requestId(), null), uploads));
        UsageRecords.add(http, 1);
        return dto;
    }

    private DisputeView ownDispute(UUID id) {
        DisputeView view = disputes.get(id);
        if (!view.creditorId().equals(support.creditorId())) {
            throw ResourceNotFoundException.of("dispute", id);
        }
        return view;
    }

    private CaseView ownCase(UUID id) {
        CaseView view = cases.get(id);
        if (!view.creditorId().equals(support.creditorId())) {
            throw ResourceNotFoundException.of("collection_case", id);
        }
        return view;
    }

    record CreditorResponseRequest(@NotBlank @Pattern(regexp = "CONFIRMS_DEBT|ACCEPTS_CLAIM|PARTIAL|INFORMATION") String position,
                                   @NotBlank @Size(max = 4000) String note, UUID requestId) {
    }

    record OpenDisputeRequest(@NotNull UUID caseId, @Size(max = 500) Set<UUID> titleIds,
                              @NotBlank @Pattern(regexp = "NOT_RECOGNIZED|ALREADY_PAID|WRONG_AMOUNT|FRAUD|PRESCRIBED|"
                                      + "PRODUCT_OR_SERVICE|OTHER") String type,
                              @NotBlank @Size(max = 2000) String description, Boolean blocksCollection,
                              LocalDate responseDueDate) {
    }
}
