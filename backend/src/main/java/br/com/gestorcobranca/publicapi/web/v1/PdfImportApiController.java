package br.com.gestorcobranca.publicapi.web.v1;

import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.PdfImportView;
import br.com.gestorcobranca.ingestion.application.port.in.PdfImportUseCases.UploadPdfCommand;
import br.com.gestorcobranca.organization.application.port.in.PortfolioUseCases;
import br.com.gestorcobranca.publicapi.application.port.in.gateway.PublicImports;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.web.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.UUID;

/**
 * Envio de PDF para a Importação PDF inteligente (módulo ingestion): mesmo fluxo da tela — leitura segura, mapa de
 * extração (mapa salvo do credor ou IA), verificações automáticas. A importação fica <strong>aguardando validação
 * humana</strong> na tela da assessoria e nunca é importada sem essa aprovação; acompanhe por
 * {@code GET /imports/pdf/{id}}. {@code Idempotency-Key} obrigatório e isolado por token.
 */
@RestController
@Validated
@RequestMapping(ApiPaths.PUBLIC_API_V1 + "/imports/pdf")
@Tag(name = "Importação", description = "Carga de títulos em lote (JSON, CSV ou XLSX), assíncrona e idempotente")
class PdfImportApiController {

    private final PdfImportUseCases pdfImports;
    private final PublicImports imports;
    private final PortfolioUseCases portfolios;
    private final PublicApiSupport support;

    PdfImportApiController(PdfImportUseCases pdfImports, PublicImports imports, PortfolioUseCases portfolios,
                           PublicApiSupport support) {
        this.pdfImports = pdfImports;
        this.imports = imports;
        this.portfolios = portfolios;
        this.support = support;
    }

    /** Importação PDF vista pelo credor: situação e resumo, sem nenhum dado do devedor. */
    @Schema(name = "PdfImport")
    record PdfImportDto(UUID id, UUID portfolioId,
                        @Schema(description = "RECEIVED, MAPPING, AWAITING_MAP, AWAITING_VALIDATION, APPROVED, DISCARDED ou FAILED")
                        String status,
                        String fileName, String fileSha256, int pages,
                        @Schema(description = "falso = PDF digitalizado, lido visualmente") boolean textLayer,
                        @Schema(description = "AI, TEMPLATE (mapa salvo do credor), USER ou VISUAL") String mapOrigin,
                        @Schema(description = "títulos encontrados com o mapa corrente") Integer records,
                        @Schema(description = "os totais impressos no documento conferem com a soma extraída") Boolean totalsMatch,
                        @Schema(description = "aprovação na tela abre a conferência da importação") boolean awaitingHumanValidation,
                        String failureCode, String failureMessage, Instant createdAt, Instant approvedAt) {

        static PdfImportDto of(PdfImportView v) {
            return new PdfImportDto(v.id(), v.portfolioId(), v.status(), v.fileName(), v.fileSha256(), v.pages(),
                    v.textLayer(), v.mapOrigin(), v.summary() == null ? null : v.summary().records(),
                    v.summary() == null ? null : v.summary().totalsMatch(),
                    "AWAITING_VALIDATION".equals(v.status()) || "AWAITING_MAP".equals(v.status())
                            || "RECEIVED".equals(v.status()) || "MAPPING".equals(v.status()),
                    v.failureCode(), v.failureMessage(), v.createdAt(), v.approvedAt());
        }
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('SCOPE_imports:write')")
    @Operation(operationId = "uploadPdfImport", summary = "Enviar PDF (Importação PDF inteligente)", description = "PDF de até 20 MB, sem senha e sem "
            + "JavaScript, associado a uma carteira do credor. O sistema encontra devedor, títulos e totais e a "
            + "importação fica aguardando a validação humana na assessoria — nunca é importada só pela API. O mesmo "
            + "PDF já em andamento ou aprovado na carteira é recusado (409 `pdf_import.duplicate_file`).",
            parameters = @Parameter(name = "Idempotency-Key", in = ParameterIn.HEADER, required = true,
                    description = "8 a 80 caracteres; repetir com o mesmo arquivo devolve a mesma importação",
                    schema = @Schema(type = "string", example = "pdf-2026-10-30-001")))
    PdfImportDto upload(@RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
                        @RequestPart("file") MultipartFile file, @RequestParam UUID portfolioId) {
        if (!portfolios.get(portfolioId).creditorId().equals(support.creditorId())) {
            throw ResourceNotFoundException.of("portfolio", portfolioId);
        }
        String key = imports.scopedIdempotencyKey(idempotencyKey, true);
        return PdfImportDto.of(pdfImports.upload(new UploadPdfCommand(portfolioId, file.getOriginalFilename(),
                file.getContentType(), file.getSize(), file::getInputStream, "PUBLIC_API", key)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('SCOPE_imports:read')")
    @Operation(operationId = "getPdfImport", summary = "Importação PDF", description = "Situação e resumo (títulos encontrados, totais do documento).")
    PdfImportDto get(@PathVariable UUID id) {
        return PdfImportDto.of(pdfImports.get(id));
    }
}
