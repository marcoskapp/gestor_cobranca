package br.com.gestorcobranca.ingestion.web;

import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases.AlertDecisionCommand;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases.AlertView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases.AnalysisView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases.ApprovalCommand;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases.BreakdownRowView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases.CommitCommand;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases.FormatCommand;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases.ReviewSearch;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases.ReviewSummaryView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases.ReviewView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases.StartReviewCommand;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases.TotalsCheckView;
import br.com.gestorcobranca.ingestion.application.port.in.ImportReviewUseCases.TotalsCommand;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Conferência da importação (assistente em passos — {@code docs/tecnico/desenho/importacao.md} §6). Leituras exigem
 * {@code IMPORT_VIEW}; ações, {@code IMPORT_EXECUTE}; a aprovação de segunda pessoa, {@code IMPORT_APPROVE}.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/ingestion/reviews")
class ImportReviewController {

    private final ImportReviewUseCases reviews;

    ImportReviewController(ImportReviewUseCases reviews) {
        this.reviews = reviews;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('IMPORT_EXECUTE')")
    ReviewView start(@RequestPart("file") MultipartFile file, @RequestParam UUID portfolioId,
                     @RequestParam(required = false) UUID layoutId) {
        return reviews.start(new StartReviewCommand(portfolioId, layoutId, file.getOriginalFilename(), file.getContentType(),
                file.getSize(), file::getInputStream));
    }

    @PostMapping(path = "/from-batch", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('IMPORT_EXECUTE')")
    ReviewView startFromBatch(@Valid @RequestBody FromBatchRequest request) {
        return reviews.startFromBatch(request.batchId());
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('IMPORT_VIEW', 'IMPORT_APPROVE')")
    PageResponse<ReviewSummaryView> search(@RequestParam(required = false) UUID creditorId,
                                           @RequestParam(required = false) UUID portfolioId,
                                           @RequestParam(required = false) @Size(max = 100) String status,
                                           @RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer size) {
        return reviews.search(new ReviewSearch(creditorId, portfolioId, status), PageQuery.of(page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('IMPORT_VIEW', 'IMPORT_APPROVE')")
    ReviewView get(@PathVariable UUID id) {
        return reviews.get(id);
    }

    @PutMapping("/{id}/step")
    @PreAuthorize("hasAuthority('IMPORT_EXECUTE')")
    ReviewView step(@PathVariable UUID id, @Valid @RequestBody StepRequest request) {
        return reviews.moveTo(id, request.step());
    }

    @PostMapping("/{id}/format")
    @PreAuthorize("hasAuthority('IMPORT_EXECUTE')")
    ReviewView format(@PathVariable UUID id, @Valid @RequestBody FormatRequest request) {
        return reviews.confirmFormat(id, new FormatCommand(request.batchId(), request.decisions()));
    }

    @PostMapping("/{id}/totals")
    @PreAuthorize("hasAuthority('IMPORT_EXECUTE')")
    TotalsCheckView totals(@PathVariable UUID id, @Valid @RequestBody TotalsRequest request) {
        return reviews.checkTotals(id, new TotalsCommand(request.rows(), request.principalTotal(), request.balanceTotal(),
                request.contractAmountTotal()));
    }

    @GetMapping("/{id}/totals/breakdown")
    @PreAuthorize("hasAnyAuthority('IMPORT_VIEW', 'IMPORT_APPROVE')")
    List<BreakdownRowView> breakdown(@PathVariable UUID id) {
        return reviews.breakdown(id);
    }

    @GetMapping("/{id}/alerts")
    @PreAuthorize("hasAnyAuthority('IMPORT_VIEW', 'IMPORT_APPROVE')")
    PageResponse<AlertView> alerts(@PathVariable UUID id, @RequestParam(required = false) @Size(max = 40) String type,
                                   @RequestParam(required = false) @Size(max = 20) String status,
                                   @RequestParam(required = false) Integer page,
                                   @RequestParam(required = false) Integer size) {
        return reviews.alerts(id, type, status, PageQuery.of(page, size));
    }

    @PostMapping("/{id}/alerts/decisions")
    @PreAuthorize("hasAuthority('IMPORT_EXECUTE')")
    AnalysisView decide(@PathVariable UUID id, @Valid @RequestBody DecisionRequest request) {
        return reviews.decideAlerts(id, new AlertDecisionCommand(request.alertIds(), request.allOpen(), request.type(),
                request.decision(), request.justification()));
    }

    @PostMapping("/{id}/commit")
    @PreAuthorize("hasAuthority('IMPORT_EXECUTE')")
    ReviewView commit(@PathVariable UUID id, @Valid @RequestBody CommitRequest request) {
        return reviews.commit(id, new CommitCommand(request.confirmationText(), request.forceReason()));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('IMPORT_APPROVE')")
    ReviewView approve(@PathVariable UUID id, @Valid @RequestBody ApproveRequest request) {
        return reviews.approve(id, new ApprovalCommand(request.confirmationText(), request.note()));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAuthority('IMPORT_APPROVE')")
    ReviewView reject(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
        return reviews.reject(id, request.reason());
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('IMPORT_EXECUTE')")
    ReviewView cancel(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
        return reviews.cancel(id, request.reason());
    }

    record FromBatchRequest(@NotNull UUID batchId) {
    }

    record StepRequest(@Min(1) @Max(6) int step) {
    }

    /** @param decisions coluna → COMMA, DOT, PER_VALUE_GROUPING ou PER_VALUE_DECIMAL */
    record FormatRequest(UUID batchId, @Size(max = 10) Map<String, @Size(max = 30) String> decisions) {
    }

    record TotalsRequest(@PositiveOrZero Long rows, @Digits(integer = 17, fraction = 2) BigDecimal principalTotal,
                         @Digits(integer = 17, fraction = 2) BigDecimal balanceTotal,
                         @Digits(integer = 17, fraction = 2) BigDecimal contractAmountTotal) {
    }

    record DecisionRequest(@Size(max = 1000) List<UUID> alertIds, boolean allOpen, @Size(max = 40) String type,
                           @NotBlank @Size(max = 20) String decision, @Size(max = 500) String justification) {
    }

    record CommitRequest(@NotBlank @Size(max = 40) String confirmationText, @Size(max = 500) String forceReason) {
    }

    record ApproveRequest(@NotBlank @Size(max = 40) String confirmationText, @Size(max = 500) String note) {
    }

    record ReasonRequest(@NotBlank @Size(max = 500) String reason) {
    }
}
