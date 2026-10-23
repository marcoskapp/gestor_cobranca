package br.com.gestorcobranca.settlement.web;

import br.com.gestorcobranca.settlement.application.port.in.CommissionLedgerQueries.ReceiptView;
import br.com.gestorcobranca.settlement.application.port.in.StatementExportUseCases;
import br.com.gestorcobranca.settlement.application.port.in.StatementExportUseCases.ExportFile;
import br.com.gestorcobranca.settlement.application.port.in.StatementExportUseCases.ExportView;
import br.com.gestorcobranca.settlement.application.port.in.StatementUseCases;
import br.com.gestorcobranca.settlement.application.port.in.StatementUseCases.AdjustmentCommand;
import br.com.gestorcobranca.settlement.application.port.in.StatementUseCases.AdjustmentView;
import br.com.gestorcobranca.settlement.application.port.in.StatementUseCases.CreateStatementCommand;
import br.com.gestorcobranca.settlement.application.port.in.StatementUseCases.ExpenseView;
import br.com.gestorcobranca.settlement.application.port.in.StatementUseCases.PeriodSuggestion;
import br.com.gestorcobranca.settlement.application.port.in.StatementUseCases.SendCommand;
import br.com.gestorcobranca.settlement.application.port.in.StatementUseCases.StatementFilter;
import br.com.gestorcobranca.settlement.application.port.in.StatementUseCases.StatementView;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.web.ApiPaths;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Demonstrativos de prestação de contas: ciclo, ajustes, exportações e resposta do credor. */
@RestController
@RequestMapping(ApiPaths.V1 + "/settlement")
class StatementController {

    private static final String READ = "hasAnyAuthority('SETTLEMENT_VIEW', 'SETTLEMENT_CLOSE', 'SETTLEMENT_APPROVE')";

    private final StatementUseCases statements;
    private final StatementExportUseCases exports;

    StatementController(StatementUseCases statements, StatementExportUseCases exports) {
        this.statements = statements;
        this.exports = exports;
    }

    @GetMapping("/statements")
    @PreAuthorize(READ)
    PageResponse<StatementView> search(@RequestParam(required = false) UUID creditorId,
                                       @RequestParam(required = false) String status,
                                       @RequestParam(required = false) Integer page,
                                       @RequestParam(required = false) Integer size) {
        return statements.search(new StatementFilter(creditorId, status), PageQuery.of(page, size));
    }

    @GetMapping("/statements/period-suggestion")
    @PreAuthorize("hasAnyAuthority('SETTLEMENT_VIEW', 'SETTLEMENT_CLOSE')")
    PeriodSuggestion suggestion(@RequestParam UUID creditorId) {
        return statements.suggestPeriod(creditorId);
    }

    @PostMapping("/statements")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('SETTLEMENT_CLOSE')")
    StatementView create(@Valid @RequestBody CreateRequest request) {
        return statements.create(new CreateStatementCommand(request.creditorId(), request.periodStart(),
                request.periodEnd()));
    }

    @GetMapping("/statements/{id}")
    @PreAuthorize(READ)
    StatementView get(@PathVariable UUID id) {
        return statements.get(id);
    }

    @GetMapping("/statements/{id}/receipts")
    @PreAuthorize(READ)
    PageResponse<ReceiptView> receipts(@PathVariable UUID id, @RequestParam(required = false) Integer page,
                                       @RequestParam(required = false) Integer size) {
        return statements.receipts(id, PageQuery.of(page, size));
    }

    @GetMapping("/statements/{id}/expenses")
    @PreAuthorize(READ)
    List<ExpenseView> expenses(@PathVariable UUID id) {
        return statements.expenses(id);
    }

    @PostMapping("/statements/{id}/calculate")
    @PreAuthorize("hasAuthority('SETTLEMENT_CLOSE')")
    StatementView calculate(@PathVariable UUID id) {
        return statements.calculate(id);
    }

    @PostMapping("/statements/{id}/review")
    @PreAuthorize("hasAuthority('SETTLEMENT_CLOSE')")
    StatementView review(@PathVariable UUID id) {
        return statements.review(id);
    }

    @PostMapping("/statements/{id}/approve")
    @PreAuthorize("hasAuthority('SETTLEMENT_APPROVE')")
    StatementView approve(@PathVariable UUID id) {
        return statements.approve(id);
    }

    @PostMapping("/statements/{id}/send")
    @PreAuthorize("hasAuthority('SETTLEMENT_CLOSE')")
    StatementView send(@PathVariable UUID id, @Valid @RequestBody SendRequest request) {
        return statements.send(id, new SendCommand(request.channel(), request.reference(), request.exportIds()));
    }

    @PostMapping("/statements/{id}/acknowledge")
    @PreAuthorize("hasAuthority('SETTLEMENT_CLOSE')")
    StatementView acknowledge(@PathVariable UUID id, @Valid @RequestBody NoteRequest request) {
        return statements.acknowledge(id, request.note());
    }

    @PostMapping("/statements/{id}/contest")
    @PreAuthorize("hasAuthority('SETTLEMENT_CLOSE')")
    StatementView contest(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
        return statements.contest(id, request.reason());
    }

    @PostMapping("/statements/{id}/cancel")
    @PreAuthorize("hasAuthority('SETTLEMENT_CLOSE')")
    StatementView cancel(@PathVariable UUID id, @Valid @RequestBody ReasonRequest request) {
        return statements.cancel(id, request.reason());
    }

    @GetMapping("/statements/{id}/adjustments")
    @PreAuthorize(READ)
    List<AdjustmentView> adjustments(@PathVariable UUID id) {
        return statements.adjustments(id);
    }

    @PostMapping("/statements/{id}/adjustments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('SETTLEMENT_CLOSE')")
    AdjustmentView requestAdjustment(@PathVariable UUID id, @Valid @RequestBody AdjustmentRequest r) {
        return statements.requestAdjustment(id, new AdjustmentCommand(r.category(), r.amount(), r.reason(),
                r.relatedStatementId()));
    }

    @PostMapping("/adjustments/{id}/approve")
    @PreAuthorize("hasAuthority('SETTLEMENT_APPROVE')")
    AdjustmentView approveAdjustment(@PathVariable UUID id, @Valid @RequestBody NoteRequest request) {
        return statements.approveAdjustment(id, request.note());
    }

    @PostMapping("/adjustments/{id}/reject")
    @PreAuthorize("hasAuthority('SETTLEMENT_APPROVE')")
    AdjustmentView rejectAdjustment(@PathVariable UUID id, @Valid @RequestBody NoteRequest request) {
        return statements.rejectAdjustment(id, request.note());
    }

    @GetMapping("/statements/{id}/exports")
    @PreAuthorize(READ)
    List<ExportView> exportsOf(@PathVariable UUID id) {
        return exports.list(id);
    }

    @PostMapping("/statements/{id}/exports")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('SETTLEMENT_CLOSE')")
    ExportView export(@PathVariable UUID id, @Valid @RequestBody ExportRequest request) {
        return exports.export(id, request.format());
    }

    /** Download do arquivo (registra FILE_DOWNLOADED); o SHA-256 vai no cabeçalho para conferência. */
    @GetMapping("/exports/{id}/content")
    @PreAuthorize(READ)
    ResponseEntity<InputStreamResource> download(@PathVariable UUID id) {
        ExportFile file = exports.download(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-SHA256", file.sha256())
                .contentType(MediaType.parseMediaType(file.contentType()))
                .contentLength(file.size())
                .body(new InputStreamResource(file.content()));
    }

    record CreateRequest(@NotNull UUID creditorId, LocalDate periodStart, LocalDate periodEnd) {
    }

    record SendRequest(@NotBlank @Size(max = 20) String channel, @Size(max = 200) String reference,
                       @NotEmpty @Size(max = 10) List<UUID> exportIds) {
    }

    record NoteRequest(@Size(max = 2000) String note) {
    }

    record ReasonRequest(@NotBlank @Size(max = 2000) String reason) {
    }

    record AdjustmentRequest(@NotBlank @Size(max = 40) String category,
                             @NotNull @Digits(integer = 15, fraction = 2) BigDecimal amount,
                             @NotBlank @Size(min = 10, max = 2000) String reason, UUID relatedStatementId) {
    }

    record ExportRequest(@NotBlank @Size(max = 10) String format) {
    }
}
