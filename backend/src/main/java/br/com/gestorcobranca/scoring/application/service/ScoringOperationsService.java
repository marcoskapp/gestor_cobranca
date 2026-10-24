package br.com.gestorcobranca.scoring.application.service;

import br.com.gestorcobranca.scoring.application.port.in.ScoringOperations;
import br.com.gestorcobranca.scoring.application.port.out.SnapshotStore;
import br.com.gestorcobranca.scoring.domain.model.ScoringRun;
import br.com.gestorcobranca.scoring.domain.repository.MonitoringReportRepository;
import br.com.gestorcobranca.scoring.domain.repository.ScoringRunRepository;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Execuções, monitoramento e recuperado × esperado. Execuções e monitoramento envolvem todos os
 * credores (exigem o escopo completo); o recuperado × esperado é filtrado pelo escopo do ator.
 */
@Service
@Transactional
class ScoringOperationsService implements ScoringOperations {

    private static final Set<String> GROUPS = Set.of("NONE", "CREDITOR", "PORTFOLIO", "ASSIGNEE");
    private static final long MAX_RANGE_DAYS = 400;

    private final ScoreBatchService batch;
    private final ScoringRunRepository runs;
    private final MonitoringReportRepository reports;
    private final SnapshotStore snapshots;
    private final ScoringViews views;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;

    ScoringOperationsService(ScoreBatchService batch, ScoringRunRepository runs, MonitoringReportRepository reports,
                             SnapshotStore snapshots, ScoringViews views, AccessScope accessScope,
                             CurrentActor currentActor) {
        this.batch = batch;
        this.runs = runs;
        this.reports = reports;
        this.snapshots = snapshots;
        this.views = views;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
    }

    @Override
    public RunView startBatch() {
        accessScope.requireAllCreditors("scoring_run");
        return batch.start(ScoringRun.Trigger.MANUAL, currentActor.actorId()).map(views::run)
                .orElseThrow(() -> new ConflictException("scoring.legal_basis_missing",
                        "Score desabilitado: registre a base legal do tratamento (configurações do score)"));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<RunView> runs(String kind, PageQuery page) {
        accessScope.requireAllCreditors("scoring_run");
        ScoringRun.Kind k = kind == null || kind.isBlank() ? ScoringRun.Kind.BATCH
                : ScoringViews.parse(ScoringRun.Kind.class, kind, "scoring.run.kind_invalid", "Tipo de execução");
        return PageResponse.of(runs.findByKind(k, page.toPageable(Sort.by(Sort.Direction.DESC, "startedAt"))),
                views::run);
    }

    @Override
    @Transactional(readOnly = true)
    public RunView run(UUID runId) {
        accessScope.requireAllCreditors("scoring_run");
        return runs.findById(runId).map(views::run).orElseThrow(() -> new ResourceNotFoundException("scoring_run", runId));
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<MonitoringView> monitoring(PageQuery page) {
        accessScope.requireAllCreditors("scoring_monitoring");
        return PageResponse.of(reports.findAllByOrderByReferenceDateDesc(page.toPageable(Sort.unsorted())),
                views::monitoring);
    }

    @Override
    @Transactional(readOnly = true)
    public ExpectedRecoveryReport expectedRecovery(LocalDate from, LocalDate to, UUID creditorId, UUID portfolioId,
                                                   String groupBy) {
        Guard.require(from != null && to != null && !to.isBefore(from), "scoring.range_invalid", "Período inválido");
        Guard.require(ChronoUnit.DAYS.between(from, to) <= MAX_RANGE_DAYS, "scoring.range_too_long",
                "Período máximo de 400 dias");
        String group = groupBy == null || groupBy.isBlank() ? "NONE" : groupBy.trim().toUpperCase(Locale.ROOT);
        Guard.require(GROUPS.contains(group), "scoring.group_by_invalid", "Agrupamento inválido");
        Set<UUID> creditors = accessScope.restrictCreditors(creditorId == null ? Set.of() : Set.of(creditorId));
        if ((creditorId != null || !accessScope.hasAllCreditors()) && creditors.isEmpty()) {
            return new ExpectedRecoveryReport(from, to, group, List.of());
        }
        List<ExpectedRecoveryLine> lines = snapshots.expectedRecovery(from, to, creditors, portfolioId, group).stream()
                .map(r -> new ExpectedRecoveryLine(r.groupKey(), r.cases(), r.expectedPayers(), r.realizedPayers(),
                        r.expectedAmount(), r.realizedAmount(), r.expectedAmount() == null || r.expectedAmount().signum() == 0
                        ? null : r.realizedAmount().divide(r.expectedAmount(), 4, RoundingMode.HALF_EVEN)))
                .toList();
        return new ExpectedRecoveryReport(from, to, group, lines);
    }
}
