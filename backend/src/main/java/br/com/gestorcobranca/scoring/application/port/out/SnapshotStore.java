package br.com.gestorcobranca.scoring.application.port.out;

import br.com.gestorcobranca.scoring.domain.model.FeatureVector;
import br.com.gestorcobranca.scoring.domain.model.TrainingRow;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Snapshots de features por data de referência ({@code scoring.feature_snapshot}) com o desfecho
 * apurado depois da janela, e o recuperado × esperado por safra ({@code scoring.cohort_outcome}).
 */
public interface SnapshotStore {

    /**
     * @param outcomeDueAt fim da janela de desfecho (referência + N dias)
     */
    record SnapshotRow(UUID runId, LocalDate referenceDate, Instant referenceAt, UUID caseId, UUID debtorId,
                       UUID creditorId, UUID portfolioId, UUID assigneeUserId, String caseStatus, BigDecimal openBalance,
                       FeatureVector features, UUID championModelId, double championProbability, int championScore,
                       String band, UUID challengerModelId, Double challengerProbability, Instant outcomeDueAt) {
    }

    /** Insere os snapshots (um por caso e data; repetição é ignorada). */
    void insert(List<SnapshotRow> rows);

    /**
     * Apura o desfecho dos snapshots cuja janela terminou há pelo menos a carência: pagamento
     * confirmado do devedor na carteira com data dentro de (referência, fim da janela] e sem estorno
     * conhecido. Até {@code limit} linhas por chamada.
     *
     * @return linhas apuradas
     */
    int resolveDue(Instant dueBefore, Instant now, int limit);

    /** Observações com desfecho apurado e data de referência no intervalo (as mais recentes até o limite). */
    List<TrainingRow> trainingRows(LocalDate from, LocalDate to, int limit);

    /** População escorada num snapshot: features e probabilidade do campeão. */
    record PopulationRow(FeatureVector features, double championProbability) {
    }

    List<PopulationRow> population(UUID runId);

    /** Desfecho realizado por linha das safras apuradas (desempenho realizado). */
    record OutcomeRow(LocalDate referenceDate, UUID championModelId, double championProbability, String band,
                      UUID challengerModelId, Double challengerProbability, boolean paid) {
    }

    List<OutcomeRow> resolvedOutcomes(LocalDate from, LocalDate to);

    /**
     * Taxa de pagamento por carteira nas safras apuradas no intervalo (carteiras com ao menos
     * {@code minRows} observações) e a da assessoria (chave nula).
     */
    Map<UUID, Double> portfolioRates(LocalDate from, LocalDate to, int minRows);

    /** Ainda há snapshot da execução sem desfecho apurado. */
    boolean hasUnresolved(UUID runId);

    /** Soma de uma safra por credor × carteira × responsável × modelo campeão. */
    record CohortAggregate(UUID creditorId, UUID portfolioId, UUID assigneeUserId, UUID modelId, long cases,
                           double expectedPayers, long realizedPayers, BigDecimal probabilityWeightedBalance,
                           BigDecimal realizedAmount) {
    }

    List<CohortAggregate> cohortAggregates(UUID runId);

    record CohortOutcome(UUID cohortId, UUID runId, LocalDate referenceDate, int outcomeDays, UUID creditorId,
                         UUID portfolioId, UUID assigneeUserId, UUID modelId, int modelVersion, long cases,
                         BigDecimal expectedPayers, long realizedPayers, BigDecimal expectedAmount,
                         BigDecimal realizedAmount, Instant measuredAt) {
    }

    void saveCohorts(List<CohortOutcome> cohorts);

    /** Recuperado × esperado agregado no intervalo de datas de referência, por grupo. */
    record ExpectedRecoveryRow(String groupKey, long cases, BigDecimal expectedPayers, long realizedPayers,
                               BigDecimal expectedAmount, BigDecimal realizedAmount) {
    }

    /**
     * @param groupBy     CREDITOR, PORTFOLIO, ASSIGNEE ou NONE
     * @param creditorIds vazio = todos (já restrito ao escopo)
     */
    List<ExpectedRecoveryRow> expectedRecovery(LocalDate from, LocalDate to, java.util.Collection<UUID> creditorIds,
                                               UUID portfolioId, String groupBy);

    /** Histórico do caso nos snapshots (mais recente primeiro). */
    record HistoryRow(LocalDate referenceDate, int score, String band, UUID modelId, Boolean paid) {
    }

    List<HistoryRow> history(UUID caseId, int limit);

    /** Retenção: apaga snapshots apurados com referência anterior à data. */
    int purgeResolvedBefore(LocalDate date, int limit);

    /** Anonimização: apaga os não apurados e desvincula o devedor dos apurados (estatística preservada). */
    int anonymizeDebtor(UUID debtorId);
}
