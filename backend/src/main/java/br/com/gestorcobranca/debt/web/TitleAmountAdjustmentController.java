package br.com.gestorcobranca.debt.web;

import br.com.gestorcobranca.debt.application.port.in.TitleAmountAdjustmentUseCases;
import br.com.gestorcobranca.debt.application.port.in.TitleAmountAdjustmentUseCases.AdjustmentCommand;
import br.com.gestorcobranca.debt.application.port.in.TitleAmountAdjustmentUseCases.AdjustmentContext;
import br.com.gestorcobranca.debt.application.port.in.TitleAmountAdjustmentUseCases.AdjustmentView;
import br.com.gestorcobranca.debt.application.port.in.TitleAmountAdjustmentUseCases.DivergenceView;
import br.com.gestorcobranca.debt.application.port.in.TitleAmountAdjustmentUseCases.SettingsView;
import br.com.gestorcobranca.debt.application.port.in.TitleAmountAdjustmentUseCases.TitleAmountHistory;
import br.com.gestorcobranca.debt.application.port.in.TitlePrescriptionUseCases.EvidenceDownload;
import br.com.gestorcobranca.debt.application.port.in.TitlePrescriptionUseCases.EvidenceUpload;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/** Correção manual do valor do título: pedido, aprovação (RB06), histórico, divergência com a carga e limite. */
@RestController
@RequestMapping(ApiPaths.V1)
class TitleAmountAdjustmentController {

    private static final String READ = "hasAuthority('DEBTOR_VIEW') or hasAuthority('CASE_VIEW')";
    private static final String ADJUST = "hasAuthority('TITLE_AMOUNT_ADJUST')";
    private static final String APPROVE = "hasAuthority('TITLE_AMOUNT_APPROVE')";

    private final TitleAmountAdjustmentUseCases adjustments;

    TitleAmountAdjustmentController(TitleAmountAdjustmentUseCases adjustments) {
        this.adjustments = adjustments;
    }

    @GetMapping("/titles/{id}/amount-adjustment-context")
    @PreAuthorize(ADJUST)
    AdjustmentContext context(@PathVariable UUID id) {
        return adjustments.context(id);
    }

    @GetMapping("/titles/{id}/amount-adjustments")
    @PreAuthorize(READ)
    TitleAmountHistory history(@PathVariable UUID id) {
        return adjustments.history(id);
    }

    @PostMapping("/titles/{id}/amount-adjustments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(ADJUST)
    AdjustmentView request(@PathVariable UUID id, @Valid @RequestBody AdjustmentRequest request) {
        return adjustments.request(id, request.toCommand(), null);
    }

    /** Com evidência: partes {@code adjustment} (JSON) e {@code file} (PDF, PNG ou JPEG, conferido pelo conteúdo). */
    @PostMapping(path = "/titles/{id}/amount-adjustments", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(ADJUST)
    AdjustmentView requestWithEvidence(@PathVariable UUID id, @Valid @RequestPart("adjustment") AdjustmentRequest request,
                                       @RequestPart("file") MultipartFile file) {
        try {
            return adjustments.request(id, request.toCommand(), new EvidenceUpload(file.getOriginalFilename(),
                    file.getContentType(), file.getSize(), file.getInputStream()));
        } catch (IOException e) {
            throw new UncheckedIOException("Falha ao ler o arquivo enviado", e);
        }
    }

    @PostMapping("/title-amount-adjustments/{adjustmentId}/approve")
    @PreAuthorize(APPROVE)
    AdjustmentView approve(@PathVariable UUID adjustmentId, @Valid @RequestBody(required = false) NoteRequest request) {
        return adjustments.approve(adjustmentId, request == null ? null : request.note());
    }

    @PostMapping("/title-amount-adjustments/{adjustmentId}/reject")
    @PreAuthorize(APPROVE)
    AdjustmentView reject(@PathVariable UUID adjustmentId, @Valid @RequestBody NoteRequest request) {
        return adjustments.reject(adjustmentId, request.note());
    }

    @PostMapping("/title-amount-adjustments/{adjustmentId}/cancel")
    @PreAuthorize(ADJUST)
    AdjustmentView cancel(@PathVariable UUID adjustmentId, @Valid @RequestBody(required = false) NoteRequest request) {
        return adjustments.cancel(adjustmentId, request == null ? null : request.note());
    }

    @GetMapping("/title-amount-adjustments/pending")
    @PreAuthorize(APPROVE)
    PageResponse<AdjustmentView> pending(@RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size) {
        return adjustments.pendingApprovals(PageQuery.of(page, size));
    }

    @GetMapping("/title-amount-adjustments/{adjustmentId}/evidence")
    @PreAuthorize(READ)
    ResponseEntity<InputStreamResource> evidence(@PathVariable UUID adjustmentId) {
        EvidenceDownload file = adjustments.evidence(adjustmentId);
        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(file.contentType() == null ? MediaType.APPLICATION_OCTET_STREAM
                        : MediaType.parseMediaType(file.contentType()));
        if (file.size() >= 0) {
            builder.contentLength(file.size());
        }
        return builder.body(new InputStreamResource(file.content()));
    }

    @GetMapping("/title-amount-divergences")
    @PreAuthorize(ADJUST)
    PageResponse<DivergenceView> divergences(@RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size) {
        return adjustments.openDivergences(PageQuery.of(page, size));
    }

    @PostMapping("/title-amount-divergences/{divergenceId}/resolve")
    @PreAuthorize(ADJUST)
    DivergenceView resolve(@PathVariable UUID divergenceId, @Valid @RequestBody ResolveRequest request) {
        return adjustments.resolveDivergence(divergenceId, request.decision(), request.note());
    }

    @GetMapping("/title-amount-adjustments/settings")
    @PreAuthorize(ADJUST + " or " + APPROVE)
    SettingsView settings() {
        return adjustments.settings();
    }

    @PutMapping("/title-amount-adjustments/settings")
    @PreAuthorize(APPROVE)
    SettingsView updateSettings(@Valid @RequestBody SettingsRequest request) {
        return adjustments.updateSettings(request.approvalThreshold());
    }

    record AdjustmentRequest(@NotEmpty @Size(max = 5) Map<@NotBlank String, @NotNull @DecimalMin("0.00") BigDecimal> components,
                             @NotBlank @Size(max = 30) String reason,
                             @NotBlank @Size(max = 2000) String note,
                             UUID disputeId) {

        AdjustmentCommand toCommand() {
            return new AdjustmentCommand(components, reason, note, disputeId);
        }
    }

    record NoteRequest(@Size(max = 2000) String note) {
    }

    record ResolveRequest(@NotBlank @Pattern(regexp = "KEEP_MANUAL|ACCEPT_CREDITOR") String decision,
                          @NotBlank @Size(max = 2000) String note) {
    }

    record SettingsRequest(@NotNull @DecimalMin("0.00") @DecimalMax("100000.00") BigDecimal approvalThreshold) {
    }
}
