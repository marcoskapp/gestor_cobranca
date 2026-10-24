package br.com.gestorcobranca.ingestion.web;

import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.BatchSearch;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.ControlTotalsCommand;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.DownloadableFile;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.ImportBatchView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.ImportIssueView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.ImportRecordsCommand;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.ReprocessCommand;
import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.UploadFileCommand;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.InputStreamResource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Lotes de importação (F04). O envio responde 202: o processamento é assíncrono e acompanhado
 * pelo status e pelos contadores do lote.
 * <p>
 * {@code POST /records} é a API de ingestão por integração (registros JSON com as chaves do
 * layout canônico, {@code Idempotency-Key} obrigatório). Hoje é autenticada como usuário com
 * {@code IMPORT_EXECUTE}; credenciais de integração (ator {@code INTEGRATION}) dependem do
 * suporte no módulo identity/shared.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/ingestion/batches")
class ImportBatchController {

    private final ImportBatchUseCases batches;

    ImportBatchController(ImportBatchUseCases batches) {
        this.batches = batches;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('IMPORT_EXECUTE')")
    ImportBatchView upload(@RequestPart("file") MultipartFile file, @RequestParam UUID portfolioId,
                           @RequestParam(required = false) UUID layoutId,
                           @RequestParam(required = false) String mode,
                           @RequestParam(defaultValue = "false") boolean force,
                           @RequestParam(required = false) @Size(max = 500) String reason,
                           @RequestParam(required = false) @Size(max = 10) String decimalSeparator) {
        return batches.uploadFile(new UploadFileCommand(portfolioId, layoutId, mode, force, reason,
                file.getOriginalFilename(), file.getContentType(), file.getSize(), file::getInputStream,
                decimalSeparator));
    }

    @PostMapping(path = "/records", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('IMPORT_EXECUTE')")
    ImportBatchView importRecords(@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                                  @Valid @RequestBody ImportRecordsRequest request) {
        ControlTotalsRequest totals = request.controlTotals();
        return batches.importRecords(new ImportRecordsCommand(request.portfolioId(), request.mode(), request.force(),
                request.reason(), request.records(), request.numberFormat(),
                totals == null ? null : new ControlTotalsCommand(totals.rows(), totals.principalTotal(),
                        totals.balanceTotal(), totals.contractAmountTotal())), idempotencyKey);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('IMPORT_VIEW')")
    PageResponse<ImportBatchView> search(@RequestParam(required = false) UUID creditorId,
                                         @RequestParam(required = false) UUID portfolioId,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String source,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdFrom,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant createdTo,
                                         @RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size) {
        return batches.search(new BatchSearch(creditorId, portfolioId, status, source, createdFrom, createdTo),
                PageQuery.of(page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('IMPORT_VIEW')")
    ImportBatchView get(@PathVariable UUID id) {
        return batches.get(id);
    }

    @GetMapping("/{id}/issues")
    @PreAuthorize("hasAuthority('IMPORT_VIEW')")
    PageResponse<ImportIssueView> issues(@PathVariable UUID id, @RequestParam(required = false) String severity,
                                         @RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size) {
        return batches.issues(id, severity, PageQuery.of(page, size));
    }

    /** Relatório de ocorrências em CSV (valores mascarados, células neutralizadas contra fórmulas). */
    @GetMapping("/{id}/issues/report")
    @PreAuthorize("hasAuthority('IMPORT_VIEW')")
    void issuesReport(@PathVariable UUID id, HttpServletResponse response) throws IOException {
        batches.get(id);
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename("ocorrencias-importacao-%s.csv".formatted(id)).build().toString());
        batches.exportIssues(id, response.getOutputStream());
    }

    /** Arquivo original (dados pessoais em claro): exige também {@code DEBTOR_SENSITIVE_VIEW}. */
    @GetMapping("/{id}/file")
    @PreAuthorize("hasAuthority('IMPORT_VIEW') and hasAuthority('DEBTOR_SENSITIVE_VIEW')")
    ResponseEntity<InputStreamResource> originalFile(@PathVariable UUID id) {
        DownloadableFile file = batches.originalFile(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(file.size())
                .body(new InputStreamResource(file.content()));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('IMPORT_EXECUTE')")
    ImportBatchView cancel(@PathVariable UUID id, @Valid @RequestBody CancelRequest request) {
        return batches.cancel(id, request.reason());
    }

    @PostMapping("/{id}/reprocess")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('IMPORT_EXECUTE')")
    ImportBatchView reprocess(@PathVariable UUID id, @Valid @RequestBody(required = false) ReprocessRequest request) {
        return batches.reprocess(id, request == null ? null : new ReprocessCommand(request.mode(), request.reason()));
    }

    /**
     * @param records       valores string, número ou booleano por chave canônica
     * @param numberFormat  interpretação das colunas monetárias em texto ({@code {"valor_principal": "COMMA"}})
     * @param controlTotals totais do lote; divergência falha o lote antes de gravar
     */
    record ImportRecordsRequest(@NotNull UUID portfolioId, @Size(max = 20) String mode, boolean force,
                                @Size(max = 500) String reason,
                                @NotEmpty @Size(max = 10_000) List<Map<String, Object>> records,
                                @Size(max = 10) Map<String, @Size(max = 30) String> numberFormat,
                                @Valid ControlTotalsRequest controlTotals) {
    }

    record ControlTotalsRequest(@PositiveOrZero Long rows, @Digits(integer = 17, fraction = 2) BigDecimal principalTotal,
                                @Digits(integer = 17, fraction = 2) BigDecimal balanceTotal,
                                @Digits(integer = 17, fraction = 2) BigDecimal contractAmountTotal) {
    }

    record CancelRequest(@NotBlank @Size(max = 500) String reason) {
    }

    record ReprocessRequest(@Size(max = 20) String mode, @Size(max = 500) String reason) {
    }
}
