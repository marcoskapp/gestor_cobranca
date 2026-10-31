package br.com.gestorcobranca.ingestion.web;

import br.com.gestorcobranca.ingestion.application.port.in.ImportBatchUseCases.DownloadableFile;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.ApproveCommand;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.PageImage;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.PdfImportSearch;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.PdfImportSummaryView;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.PdfImportView;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.PdfRecordView;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.PdfTemplateView;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.RecordDecisionCommand;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.RecordDecisionResult;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.RecordFacets;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.RecordFilter;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.SaveTemplateCommand;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.UpdateMapCommand;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.UploadPdfCommand;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Importação PDF inteligente ({@code docs/tecnico/desenho/importacao-pdf.md} §8). Mesmas permissões da importação:
 * leitura {@code IMPORT_VIEW}, ações {@code IMPORT_EXECUTE}; imagem da página e valores sem máscara com
 * {@code IMPORT_EXECUTE} ou {@code DEBTOR_SENSITIVE_VIEW} (registrados no log de acesso); arquivo original com
 * {@code DEBTOR_SENSITIVE_VIEW}.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/ingestion/pdf-imports")
class PdfImportController {

    private final PdfImportUseCases pdfImports;

    PdfImportController(PdfImportUseCases pdfImports) {
        this.pdfImports = pdfImports;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('IMPORT_EXECUTE')")
    PdfImportView upload(@RequestPart("file") MultipartFile file, @RequestParam UUID portfolioId) {
        return pdfImports.upload(new UploadPdfCommand(portfolioId, file.getOriginalFilename(), file.getContentType(),
                file.getSize(), file::getInputStream, "SCREEN", null));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('IMPORT_VIEW')")
    PageResponse<PdfImportSummaryView> search(@RequestParam(required = false) UUID creditorId,
                                              @RequestParam(required = false) UUID portfolioId,
                                              @RequestParam(required = false) @Size(max = 200) String status,
                                              @RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer size) {
        return pdfImports.search(new PdfImportSearch(creditorId, portfolioId, status), PageQuery.of(page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('IMPORT_VIEW')")
    PdfImportView get(@PathVariable UUID id) {
        return pdfImports.get(id);
    }

    @GetMapping("/{id}/records")
    @PreAuthorize("hasAuthority('IMPORT_VIEW')")
    PageResponse<PdfRecordView> records(@PathVariable UUID id,
                                        @RequestParam(required = false) @Size(max = 20) String status,
                                        @RequestParam(required = false) @Min(1) Integer debtor,
                                        @RequestParam(required = false) @Size(max = 80) String q,
                                        @RequestParam(required = false) Integer page,
                                        @RequestParam(required = false) Integer size) {
        return pdfImports.records(id, new RecordFilter(status, debtor, q), PageQuery.of(page, size));
    }

    /** Contadores por situação (com o devedor e o texto) e os devedores do documento. */
    @GetMapping("/{id}/records/facets")
    @PreAuthorize("hasAuthority('IMPORT_VIEW')")
    RecordFacets recordFacets(@PathVariable UUID id, @RequestParam(required = false) @Min(1) Integer debtor,
                              @RequestParam(required = false) @Size(max = 80) String q) {
        return pdfImports.recordFacets(id, new RecordFilter(null, debtor, q));
    }

    @GetMapping(path = "/{id}/pages/{page}", produces = MediaType.IMAGE_PNG_VALUE)
    @PreAuthorize("hasAnyAuthority('IMPORT_EXECUTE', 'DEBTOR_SENSITIVE_VIEW') and hasAuthority('IMPORT_VIEW')")
    ResponseEntity<byte[]> page(@PathVariable UUID id, @PathVariable @Min(1) @Max(10_000) int page) {
        PageImage image = pdfImports.page(id, page);
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.IMAGE_PNG).body(image.content());
    }

    @GetMapping("/{id}/file")
    @PreAuthorize("hasAuthority('IMPORT_VIEW') and hasAuthority('DEBTOR_SENSITIVE_VIEW')")
    ResponseEntity<InputStreamResource> originalFile(@PathVariable UUID id) {
        DownloadableFile file = pdfImports.originalFile(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(file.size())
                .body(new InputStreamResource(file.content()));
    }

    @PutMapping("/{id}/map")
    @PreAuthorize("hasAuthority('IMPORT_EXECUTE')")
    PdfImportView updateMap(@PathVariable UUID id, @Valid @RequestBody MapRequest request) {
        return pdfImports.updateMap(id, new UpdateMapCommand(request.map(), request.revision()));
    }

    @PostMapping("/{id}/new-map")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('IMPORT_EXECUTE')")
    PdfImportView requestNewMap(@PathVariable UUID id) {
        return pdfImports.requestNewMap(id);
    }

    @PostMapping("/{id}/records/decisions")
    @PreAuthorize("hasAuthority('IMPORT_EXECUTE')")
    RecordDecisionResult decide(@PathVariable UUID id, @Valid @RequestBody DecisionRequest request) {
        FilterRequest f = request.filter();
        return pdfImports.decideRecords(id, new RecordDecisionCommand(request.ordinals(),
                f == null ? null : new RecordFilter(f.status(), f.debtor(), f.q()), request.expectedCount(),
                request.decision(), request.justification(), request.revision()));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('IMPORT_EXECUTE')")
    PdfImportView approve(@PathVariable UUID id, @Valid @RequestBody ApproveRequest request) {
        return pdfImports.approve(id, new ApproveCommand(request.revision(), request.totalsJustification(),
                Boolean.TRUE.equals(request.visualReadingConfirmed())));
    }

    @PostMapping("/{id}/template")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('IMPORT_EXECUTE')")
    PdfTemplateView saveTemplate(@PathVariable UUID id, @Valid @RequestBody TemplateRequest request) {
        return pdfImports.saveTemplate(id, new SaveTemplateCommand(request.name(), request.revision()));
    }

    @PostMapping("/{id}/discard")
    @PreAuthorize("hasAuthority('IMPORT_EXECUTE')")
    PdfImportView discard(@PathVariable UUID id, @Valid @RequestBody DiscardRequest request) {
        return pdfImports.discard(id, request.reason());
    }

    @GetMapping("/templates")
    @PreAuthorize("hasAuthority('IMPORT_VIEW')")
    List<PdfTemplateView> templates(@RequestParam UUID creditorId,
                                    @RequestParam(defaultValue = "false") boolean includeInactive) {
        return pdfImports.templates(creditorId, includeInactive);
    }

    @PostMapping("/templates/{templateId}/deactivate")
    @PreAuthorize("hasAuthority('IMPORT_EXECUTE')")
    PdfTemplateView deactivateTemplate(@PathVariable UUID templateId) {
        return pdfImports.deactivateTemplate(templateId);
    }

    record MapRequest(@NotNull Map<String, Object> map, @Min(0) int revision) {
    }

    /**
     * Títulos pelos números ({@code ordinals}) ou pelo critério do filtro ({@code filter} + {@code expectedCount}, a
     * quantidade que o usuário viu); um dos dois.
     */
    record DecisionRequest(@Size(max = 1000) List<@NotNull @Min(1) Integer> ordinals, @Valid FilterRequest filter,
                           @Min(0) Integer expectedCount, @NotBlank @Size(max = 20) String decision,
                           @Size(max = 500) String justification, @Min(0) int revision) {
    }

    record FilterRequest(@Size(max = 20) String status, @Min(1) Integer debtor, @Size(max = 80) String q) {
    }

    record ApproveRequest(@Min(1) int revision, @Size(max = 500) String totalsJustification,
                          Boolean visualReadingConfirmed) {
    }

    record TemplateRequest(@NotBlank @Size(max = 120) String name, @Min(1) int revision) {
    }

    record DiscardRequest(@NotBlank @Size(max = 500) String reason) {
    }
}
