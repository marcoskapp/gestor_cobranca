package br.com.gestorcobranca.publicapi.web.v1;

import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.ControlTotalsCommand;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.ImportBatchView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.ImportRecordsCommand;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.UploadFileCommand;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases;
import br.com.gestorcobranca.publicapi.application.port.in.gateway.PublicImports;
import br.com.gestorcobranca.publicapi.web.v1.PublicApiModels.ImportBatchDto;
import br.com.gestorcobranca.publicapi.web.v1.PublicApiModels.ImportIssueDto;
import br.com.gestorcobranca.publicapi.web.v1.PublicApiSupport.ListResponse;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.web.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Importação de títulos em lote (módulo ingestion): mesma validação, conferência de totais, reimportação idempotente
 * por título e processamento assíncrono das telas. O envio responde 202 com o lote; acompanhe por
 * {@code GET /imports/{id}}. {@code Idempotency-Key} obrigatório: repetir a chamada devolve o mesmo lote.
 */
@RestController
@Validated
@RequestMapping(ApiPaths.PUBLIC_API_V1 + "/imports")
@Tag(name = "Importação", description = "Carga de títulos em lote (JSON, CSV ou XLSX), assíncrona e idempotente")
class ImportController {

    private final PublicImports imports;
    private final ImportBatchUseCases batches;
    private final PortfolioUseCases portfolios;
    private final PublicApiSupport support;

    ImportController(PublicImports imports, ImportBatchUseCases batches, PortfolioUseCases portfolios,
                     PublicApiSupport support) {
        this.imports = imports;
        this.batches = batches;
        this.portfolios = portfolios;
        this.support = support;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('SCOPE_imports:write')")
    @Operation(summary = "Importar registros (JSON)", description = "Até 1.000 registros por chamada, com as chaves do "
            + "layout canônico (documento, nome, id_titulo, vencimento, valor_principal…). Números em JSON não têm "
            + "ambiguidade de separador decimal; valores em texto seguem `numberFormat`. Totais de controle opcionais: "
            + "divergência falha o lote antes de gravar.",
            parameters = @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true,
                    description = "8 a 80 caracteres; repetir com o mesmo conteúdo devolve o mesmo lote",
                    schema = @Schema(type = "string", example = "carga-2026-10-30-001")))
    ImportBatchDto importRecords(@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                                 @Valid @RequestBody ImportRecordsRequest r) {
        ownPortfolio(r.portfolioId());
        ControlTotalsRequest t = r.controlTotals();
        return ImportBatchDto.of(imports.importRecords(new ImportRecordsCommand(r.portfolioId(), r.mode(), r.force(),
                r.reason(), r.records(), r.numberFormat(), t == null ? null
                : new ControlTotalsCommand(t.rows(), t.principalTotal(), t.balanceTotal(), t.contractAmountTotal())),
                idempotencyKey));
    }

    @PostMapping(path = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('SCOPE_imports:write')")
    @Operation(summary = "Importar arquivo (CSV ou XLSX)", description = "Arquivo no layout canônico ou num layout "
            + "publicado do credor (`layoutId`), até 50 MB. Tipo conferido pelo conteúdo; fórmulas recusadas.",
            parameters = @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true,
                    schema = @Schema(type = "string")))
    ImportBatchDto uploadFile(@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                              @RequestPart("file") MultipartFile file, @RequestParam UUID portfolioId,
                              @RequestParam(required = false) UUID layoutId,
                              @RequestParam(required = false) @Pattern(regexp = "IMPORT|VALIDATE_ONLY") String mode,
                              @RequestParam(defaultValue = "false") boolean force,
                              @RequestParam(required = false) @Size(max = 500) String reason,
                              @RequestParam(required = false) @Pattern(regexp = "COMMA|DOT") String decimalSeparator) {
        ownPortfolio(portfolioId);
        return ImportBatchDto.of(imports.uploadFile(new UploadFileCommand(portfolioId, layoutId, mode, force, reason,
                file.getOriginalFilename(), file.getContentType(), file.getSize(), file::getInputStream,
                decimalSeparator), idempotencyKey));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('SCOPE_imports:read')")
    @Operation(summary = "Lotes (sincronização incremental)")
    ListResponse<ImportBatchDto> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant updatedSince,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) @Min(1) @Max(500) Integer limit) {
        return support.list(batches.changes(support.creditorId(), support.query(updatedSince, cursor, limit)),
                ImportBatchDto::of);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_imports:read')")
    @Operation(summary = "Lote", description = "Situação, progresso e contadores do processamento.")
    ImportBatchDto get(@PathVariable UUID id) {
        return ImportBatchDto.of(own(id));
    }

    @GetMapping("/{id}/issues")
    @PreAuthorize("hasAuthority('SCOPE_imports:read')")
    @Operation(summary = "Ocorrências do lote", description = "Erros e avisos por linha, com valores mascarados.")
    PageResponse<ImportIssueDto> issues(@PathVariable UUID id,
                                        @RequestParam(required = false) @Pattern(regexp = "ERROR|WARNING") String severity,
                                        @RequestParam(required = false) @Min(0) Integer page,
                                        @RequestParam(required = false) @Min(1) @Max(200) Integer size) {
        own(id);
        PageResponse<ImportBatchUseCases.ImportIssueView> result = batches.issues(id, severity, PageQuery.of(page, size));
        return new PageResponse<>(result.content().stream().map(ImportIssueDto::of).toList(), result.page(),
                result.size(), result.totalElements(), result.totalPages());
    }

    @GetMapping(path = "/{id}/issues/report", produces = "text/csv")
    @PreAuthorize("hasAuthority('SCOPE_imports:read')")
    @Operation(summary = "Relatório de ocorrências (CSV)", description = "Valores mascarados e células neutralizadas "
            + "contra fórmulas.")
    void issuesReport(@PathVariable UUID id, HttpServletResponse response) throws IOException {
        own(id);
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename("ocorrencias-importacao-%s.csv".formatted(id)).build().toString());
        batches.exportIssues(id, response.getOutputStream());
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('SCOPE_imports:write')")
    @Operation(summary = "Cancelar lote", description = "Na fila: imediato. Em processamento: ao fim do bloco corrente "
            + "(o que já foi gravado permanece).")
    ImportBatchDto cancel(@PathVariable UUID id, @Valid @RequestBody CancelRequest r) {
        own(id);
        return ImportBatchDto.of(batches.cancel(id, r.reason()));
    }

    private ImportBatchView own(UUID id) {
        ImportBatchView batch = batches.get(id);
        if (!batch.creditorId().equals(support.creditorId())) {
            throw ResourceNotFoundException.of("import_batch", id);
        }
        return batch;
    }

    private void ownPortfolio(UUID portfolioId) {
        if (portfolioId == null) {
            return;
        }
        if (!portfolios.get(portfolioId).creditorId().equals(support.creditorId())) {
            throw ResourceNotFoundException.of("portfolio", portfolioId);
        }
    }

    record ImportRecordsRequest(@NotNull UUID portfolioId, @Pattern(regexp = "IMPORT|VALIDATE_ONLY") String mode,
                                boolean force, @Size(max = 500) String reason,
                                @NotEmpty @Size(max = 1000) List<Map<String, Object>> records,
                                @Size(max = 10) Map<String, @Pattern(regexp = "COMMA|DOT") String> numberFormat,
                                @Valid ControlTotalsRequest controlTotals) {
    }

    record ControlTotalsRequest(@PositiveOrZero Long rows, @Digits(integer = 17, fraction = 2) BigDecimal principalTotal,
                                @Digits(integer = 17, fraction = 2) BigDecimal balanceTotal,
                                @Digits(integer = 17, fraction = 2) BigDecimal contractAmountTotal) {
    }

    record CancelRequest(@NotBlank @Size(max = 500) String reason) {
    }
}
