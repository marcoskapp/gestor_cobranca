package br.com.gestorcobranca.scoring.web;

import br.com.gestorcobranca.scoring.application.port.in.ScoringOperations;
import br.com.gestorcobranca.scoring.application.port.in.ScoringOperations.ExpectedRecoveryReport;
import br.com.gestorcobranca.scoring.application.port.in.ScoringOperations.MonitoringView;
import br.com.gestorcobranca.scoring.application.port.in.ScoringOperations.RunView;
import br.com.gestorcobranca.scoring.application.port.in.ScoringSettingsUseCases;
import br.com.gestorcobranca.scoring.application.port.in.ScoringSettingsUseCases.LegalBasisCommand;
import br.com.gestorcobranca.scoring.application.port.in.ScoringSettingsUseCases.SettingsCommand;
import br.com.gestorcobranca.scoring.application.port.in.ScoringSettingsUseCases.SettingsView;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.web.ApiPaths;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Parâmetros, base legal, execuções, monitoramento e recuperado × esperado. */
@RestController
@RequestMapping(ApiPaths.V1 + "/scoring")
@Tag(name = "Scoring — operação", description = "Parâmetros, base legal, escoragem em lote e monitoramento")
class ScoringAdminController {

    private final ScoringSettingsUseCases settings;
    private final ScoringOperations operations;

    ScoringAdminController(ScoringSettingsUseCases settings, ScoringOperations operations) {
        this.settings = settings;
        this.operations = operations;
    }

    @GetMapping("/settings")
    @PreAuthorize("hasAuthority('SCORING_VIEW')")
    @Operation(summary = "Parâmetros do score e base legal registrada")
    SettingsView getSettings() {
        return settings.get();
    }

    @PutMapping("/settings")
    @PreAuthorize("hasAuthority('SCORING_MANAGE')")
    @Operation(summary = "Altera os parâmetros do score da assessoria")
    SettingsView updateSettings(@Valid @RequestBody SettingsRequest r) {
        return settings.update(new SettingsCommand(r.outcomeDays(), r.minCases(), r.minPositives(),
                r.minPartitionPositives(), r.validationShare(), r.l2Lambda(), r.minAuc(), r.trainingWindowDays(),
                r.snapshotIntervalDays(), r.publishMinDelta(), r.psiWarning(), r.psiAlert(), r.bandCutoffs(),
                r.defaultPaidShare(), r.autoTrain()));
    }

    @PutMapping("/settings/legal-basis")
    @PreAuthorize("hasAuthority('SCORING_MANAGE')")
    @Operation(summary = "Registra a base legal do tratamento (habilita o score)")
    SettingsView registerLegalBasis(@Valid @RequestBody LegalBasisRequest r) {
        return settings.registerLegalBasis(new LegalBasisCommand(r.legalBasis(), r.reference()));
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @PreAuthorize("hasAuthority('SCORING_MANAGE')")
    @Operation(summary = "Inicia a escoragem em lote de hoje (devolve a do dia, se já existir)")
    RunView startBatch() {
        return operations.startBatch();
    }

    @GetMapping("/runs")
    @PreAuthorize("hasAuthority('SCORING_VIEW')")
    @Operation(summary = "Execuções de escoragem (BATCH) ou de treino (TRAINING)")
    PageResponse<RunView> runs(@RequestParam(required = false) @Pattern(regexp = "BATCH|TRAINING") String kind,
                               @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return operations.runs(kind, PageQuery.of(page, size));
    }

    @GetMapping("/runs/{id}")
    @PreAuthorize("hasAuthority('SCORING_VIEW')")
    @Operation(summary = "Execução de escoragem ou treino")
    RunView run(@PathVariable UUID id) {
        return operations.run(id);
    }

    @GetMapping("/monitoring")
    @PreAuthorize("hasAuthority('SCORING_VIEW')")
    @Operation(summary = "Relatórios de estabilidade (PSI) e desempenho realizado por safra")
    PageResponse<MonitoringView> monitoring(@RequestParam(required = false) Integer page,
                                            @RequestParam(required = false) Integer size) {
        return operations.monitoring(PageQuery.of(page, size));
    }

    @GetMapping("/expected-recovery")
    @PreAuthorize("hasAuthority('SCORING_VIEW')")
    @Operation(summary = "Recuperado × esperado das safras apuradas (por credor, carteira ou responsável)")
    ExpectedRecoveryReport expectedRecovery(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                            @RequestParam(required = false) UUID creditorId,
                                            @RequestParam(required = false) UUID portfolioId,
                                            @RequestParam(defaultValue = "NONE") String groupBy) {
        return operations.expectedRecovery(from, to, creditorId, portfolioId, groupBy);
    }

    record SettingsRequest(int outcomeDays, int minCases, int minPositives, int minPartitionPositives,
                           @NotNull BigDecimal validationShare, @NotNull BigDecimal l2Lambda, @NotNull BigDecimal minAuc,
                           int trainingWindowDays, int snapshotIntervalDays, int publishMinDelta,
                           @NotNull BigDecimal psiWarning, @NotNull BigDecimal psiAlert,
                           @NotNull @Size(min = 4, max = 4) List<Integer> bandCutoffs,
                           @NotNull BigDecimal defaultPaidShare, boolean autoTrain) {
    }

    record LegalBasisRequest(@NotBlank @Pattern(regexp = "LEGITIMATE_INTEREST|CREDIT_PROTECTION") String legalBasis,
                             @NotBlank @Size(min = 5, max = 500) String reference) {
    }
}
