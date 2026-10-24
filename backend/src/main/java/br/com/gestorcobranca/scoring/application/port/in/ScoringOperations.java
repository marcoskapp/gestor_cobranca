package br.com.gestorcobranca.scoring.application.port.in;

import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Execuções (escoragem em lote e treino), monitoramento e recuperado × esperado.
 */
public interface ScoringOperations {

    /** Escoragem em lote da data de hoje (a mesma do agendamento diário, se já existir). {@code SCORING_MANAGE}. */
    RunView startBatch();

    /** @param kind BATCH ou TRAINING (nulo = todos) */
    PageResponse<RunView> runs(String kind, PageQuery page);

    RunView run(UUID runId);

    PageResponse<MonitoringView> monitoring(PageQuery page);

    /**
     * Recuperado × esperado das safras com desfecho apurado (data do score no intervalo), restrito ao
     * escopo de credores.
     *
     * @param groupBy NONE, CREDITOR, PORTFOLIO ou ASSIGNEE
     */
    ExpectedRecoveryReport expectedRecovery(LocalDate from, LocalDate to, UUID creditorId, UUID portfolioId,
                                            String groupBy);

    record RunView(UUID id, String kind, String trigger, String status, LocalDate referenceDate, Instant referenceAt,
                   boolean snapshot, int outcomeDays, UUID championModelId, UUID challengerModelId, UUID resultModelId,
                   int scored, int skipped, int published, int bandChanges, int failures, Map<String, Object> details,
                   UUID requestedBy, Instant startedAt, Instant finishedAt, Instant cohortMeasuredAt) {
    }

    record MonitoringView(UUID id, UUID runId, UUID modelId, LocalDate referenceDate, String status, int population,
                          BigDecimal scorePsi, Map<String, Object> featurePsi, List<Object> realized,
                          List<Object> alerts) {
    }

    record ExpectedRecoveryReport(LocalDate from, LocalDate to, String groupBy, List<ExpectedRecoveryLine> lines) {
    }

    /**
     * @param ratio recuperado ÷ esperado (valor); nulo sem esperado
     */
    record ExpectedRecoveryLine(String groupKey, long cases, BigDecimal expectedPayers, long realizedPayers,
                                BigDecimal expectedAmount, BigDecimal realizedAmount, BigDecimal ratio) {
    }
}
