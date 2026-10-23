package br.com.gestorcobranca.incentive.web;

import br.com.gestorcobranca.incentive.application.port.in.PeriodUseCases;
import br.com.gestorcobranca.incentive.application.port.in.PeriodUseCases.PeriodView;
import br.com.gestorcobranca.incentive.application.port.in.StatementExportUseCases;
import br.com.gestorcobranca.incentive.application.port.in.StatementExportUseCases.ExportFile;
import br.com.gestorcobranca.incentive.application.port.in.StatementExportUseCases.ExportView;
import br.com.gestorcobranca.incentive.application.port.in.StatementUseCases;
import br.com.gestorcobranca.incentive.application.port.in.StatementUseCases.AdjustmentView;
import br.com.gestorcobranca.incentive.application.port.in.StatementUseCases.DisputeCommand;
import br.com.gestorcobranca.incentive.application.port.in.StatementUseCases.DisputeView;
import br.com.gestorcobranca.incentive.application.port.in.StatementUseCases.LedgerEntryView;
import br.com.gestorcobranca.incentive.application.port.in.StatementUseCases.StatementDetail;
import br.com.gestorcobranca.incentive.application.port.in.StatementUseCases.StatementPreview;
import br.com.gestorcobranca.incentive.application.port.in.StatementUseCases.StatementSummary;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.web.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/**
 * Períodos de apuração, extratos, razão, contestação, ajuste, aprovação e exportação para a folha
 * (§5). Leitura no escopo próprio/equipe/tudo; consulta a remuneração de terceiro gera log de acesso.
 */
@RestController
@RequestMapping(ApiPaths.V1 + "/incentive")
@Tag(name = "Incentive — extratos", description = "Apuração, extrato, contestação, aprovação e exportação")
class CommissionStatementController {

    private static final String VIEW = "hasAnyAuthority('PERFORMANCE_VIEW_OWN', 'PERFORMANCE_VIEW_TEAM', "
            + "'PERFORMANCE_VIEW_ALL', 'COMMISSION_STATEMENT_MANAGE', 'COMMISSION_STATEMENT_APPROVE')";

    private final PeriodUseCases periods;
    private final StatementUseCases statements;
    private final StatementExportUseCases exports;

    CommissionStatementController(PeriodUseCases periods, StatementUseCases statements, StatementExportUseCases exports) {
        this.periods = periods;
        this.statements = statements;
        this.exports = exports;
    }

    @GetMapping("/periods")
    @PreAuthorize("hasAuthority('COMMISSION_STATEMENT_MANAGE')")
    List<PeriodView> periods() {
        return periods.list();
    }

    @PostMapping("/periods/{period}/close")
    @PreAuthorize("hasAuthority('COMMISSION_STATEMENT_MANAGE')")
    @Operation(summary = "Fecha o período (após a carência do último dia) e gera os extratos")
    PeriodView close(@PathVariable YearMonth period) {
        return periods.close(period);
    }

    @GetMapping("/statements")
    @PreAuthorize(VIEW)
    PageResponse<StatementSummary> list(@RequestParam(required = false) YearMonth period,
                                        @RequestParam(required = false) UUID userId,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(required = false) Integer page,
                                        @RequestParam(required = false) Integer size) {
        return statements.list(period, userId, status, PageQuery.of(page, size));
    }

    @GetMapping("/statements/preview")
    @PreAuthorize(VIEW)
    @Operation(summary = "Prévia em tempo real do período corrente (liberado e em carência)")
    StatementPreview preview(@RequestParam(required = false) UUID userId) {
        return statements.preview(userId);
    }

    @GetMapping("/statements/{id}")
    @PreAuthorize(VIEW)
    StatementDetail get(@PathVariable UUID id) {
        return statements.get(id);
    }

    @GetMapping("/ledger")
    @PreAuthorize(VIEW)
    @Operation(summary = "Lançamentos do razão de comissão de um negociador (padrão: o próprio)")
    PageResponse<LedgerEntryView> ledger(@RequestParam(required = false) UUID userId,
                                         @RequestParam(required = false) YearMonth period,
                                         @RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer size) {
        return statements.ledger(userId, period, PageQuery.of(page, size));
    }

    @PostMapping("/statements/{id}/recalculate")
    @PreAuthorize("hasAuthority('COMMISSION_STATEMENT_MANAGE')")
    StatementDetail recalculate(@PathVariable UUID id) {
        return statements.recalculate(id);
    }

    @PostMapping("/statements/{id}/disputes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(VIEW)
    @Operation(summary = "Contesta um item do próprio extrato (linha, lançamento ou acordo faltante)")
    DisputeView dispute(@PathVariable UUID id, @Valid @RequestBody DisputeRequest request) {
        return statements.openDispute(id, new DisputeCommand(request.lineNumber(), request.ledgerEntryId(),
                request.agreementId(), request.reason()));
    }

    @PostMapping("/statements/{id}/disputes/{disputeId}/resolve")
    @PreAuthorize("hasAuthority('COMMISSION_STATEMENT_MANAGE')")
    DisputeView resolve(@PathVariable UUID id, @PathVariable UUID disputeId,
                        @Valid @RequestBody ResolveDisputeRequest request) {
        return statements.resolveDispute(id, disputeId, request.accepted(), request.resolution(), request.adjustmentAmount());
    }

    @PostMapping("/statements/{id}/adjustments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('COMMISSION_STATEMENT_MANAGE')")
    @Operation(summary = "Ajuste manual com motivo (acima do limite exige segunda aprovação)")
    AdjustmentView adjust(@PathVariable UUID id, @Valid @RequestBody AdjustmentRequest request) {
        return statements.adjust(id, request.amount(), request.reason());
    }

    @PostMapping("/statements/{id}/adjustments/{adjustmentId}/decision")
    @PreAuthorize("hasAuthority('COMMISSION_STATEMENT_MANAGE')")
    AdjustmentView decide(@PathVariable UUID id, @PathVariable UUID adjustmentId,
                          @Valid @RequestBody DecisionRequest request) {
        return statements.decideAdjustment(id, adjustmentId, request.approve(), request.note());
    }

    @PostMapping("/statements/{id}/approve")
    @PreAuthorize("hasAuthority('COMMISSION_STATEMENT_APPROVE')")
    @Operation(summary = "Aprovação financeira (segregada de quem fechou o período e de quem ajustou)")
    StatementDetail approve(@PathVariable UUID id) {
        return statements.approve(id);
    }

    @PostMapping("/exports")
    @PreAuthorize("hasAuthority('COMMISSION_STATEMENT_APPROVE')")
    @Operation(summary = "Exporta os extratos aprovados do período para a folha (CSV ou XLSX)")
    ResponseEntity<InputStreamResource> export(@Valid @RequestBody ExportRequest request) {
        return attachment(exports.export(request.period(), request.format()));
    }

    @GetMapping("/exports")
    @PreAuthorize("hasAuthority('COMMISSION_STATEMENT_APPROVE')")
    List<ExportView> exports(@RequestParam YearMonth period) {
        return exports.list(period);
    }

    @GetMapping("/exports/{id}/file")
    @PreAuthorize("hasAuthority('COMMISSION_STATEMENT_APPROVE')")
    ResponseEntity<InputStreamResource> download(@PathVariable UUID id) {
        return attachment(exports.download(id));
    }

    private static ResponseEntity<InputStreamResource> attachment(ExportFile file) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.size())
                .body(new InputStreamResource(file.content()));
    }

    record DisputeRequest(@Min(1) Integer lineNumber, UUID ledgerEntryId, UUID agreementId,
                          @NotBlank @Size(min = 10, max = 2000) String reason) {
    }

    record ResolveDisputeRequest(boolean accepted, @NotBlank @Size(min = 10, max = 2000) String resolution,
                                 BigDecimal adjustmentAmount) {
    }

    record AdjustmentRequest(@NotNull BigDecimal amount, @NotBlank @Size(min = 10, max = 2000) String reason) {
    }

    record DecisionRequest(boolean approve, @Size(max = 2000) String note) {
    }

    record ExportRequest(@NotNull YearMonth period, @NotBlank @Pattern(regexp = "CSV|XLSX") String format) {
    }
}
