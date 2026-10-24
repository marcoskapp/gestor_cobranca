package br.com.gestorcobranca.scoring.application.port.in;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Modelos versionados e sua governança. Os modelos usam dados de todos os credores da assessoria:
 * consultas exigem {@code SCORING_VIEW} e alterações {@code SCORING_MANAGE}, sempre com acesso a
 * todos os credores. Promoção a campeão é <b>somente manual</b>, do desafiante validado, com
 * justificativa e registro da decisão; quem pediu o treino ou criou o scorecard não o promove.
 */
public interface ModelUseCases {

    List<ModelView> list();

    ModelView get(UUID modelId);

    /** Pede um treino da regressão logística (assíncrono): devolve a execução em andamento. */
    TrainingRequest train();

    /** Cria um scorecard configurado (entra como desafiante, com retroteste no histórico quando houver). */
    ModelView createScorecard(ScorecardCommand command);

    ModelView promote(UUID modelId, DecisionCommand command);

    ModelView reject(UUID modelId, DecisionCommand command);

    List<DecisionView> decisions();

    /** Catálogo de features permitidas (e o que nunca entra no score). */
    FeatureCatalog features();

    record ModelView(UUID id, int modelVersion, String kind, String status, String description, int outcomeDays,
                     Map<String, Object> definition, List<Integer> bandCutoffs, BigDecimal paidShare,
                     LocalDate trainingFrom, LocalDate trainingTo, LocalDate validationFrom, LocalDate validationTo,
                     int trainingRows, int trainingPositives, int validationRows, int validationPositives,
                     Map<String, Object> metrics, List<String> failureReasons, UUID trainedBy, Instant trainedAt,
                     Instant promotedAt, UUID promotedBy, Instant endedAt) {
    }

    record TrainingRequest(UUID runId, String status) {
    }

    record ScorecardCommand(String description, int basePoints, int pdo, int anchorPoints, BigDecimal anchorOdds,
                            List<RuleInput> rules) {
    }

    record RuleInput(String feature, List<BinInput> bins, int missingPoints) {
    }

    /** Faixa [from, to); nulo = sem limite. */
    record BinInput(BigDecimal from, BigDecimal to, int points) {
    }

    record DecisionCommand(String justification) {
    }

    record DecisionView(UUID id, UUID modelId, String decision, UUID previousChampionId, String justification,
                        Map<String, Object> comparison, UUID decidedBy, Instant decidedAt) {
    }

    record FeatureCatalog(List<FeatureInfo> allowed, List<String> neverUsed, String purpose) {
    }

    record FeatureInfo(String name, String label, String transform) {
    }
}
