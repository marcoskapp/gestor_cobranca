package br.com.gestorcobranca.scoring.application.port.in;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Score do caso e sua explicação (direito de revisão, LGPD art. 20). Leitura com
 * {@code SCORING_VIEW} e o caso visível no escopo de credores (404 fora dele).
 */
public interface CaseScoreUseCases {

    /** Score vigente com todos os fatores (contribuição por feature) e o aviso de finalidade e base legal. */
    CaseScoreView get(UUID caseId);

    /** Scores do caso nos snapshots (mais recente primeiro), com o desfecho quando já apurado. */
    List<HistoryEntry> history(UUID caseId);

    /** Recalcula agora com o campeão vigente (sob demanda). */
    CaseScoreView recalculate(UUID caseId);

    /** Revisão do score: recálculo com os dados atuais, registrado com o score anterior e o novo. */
    ReviewView review(UUID caseId, ReviewCommand command);

    List<ReviewView> reviews(UUID caseId);

    /**
     * @param probability probabilidade estimada de pagamento confirmado em {@code outcomeDays} dias
     * @param factors     todas as features com a contribuição (ordem do catálogo)
     * @param topFactors  até 5 fatores de maior efeito
     */
    record CaseScoreView(UUID caseId, UUID creditorId, UUID portfolioId, int score, String band, BigDecimal probability,
                         int outcomeDays, UUID modelId, int modelVersion, String modelKind, LocalDate referenceDate,
                         Instant calculatedAt, String trigger, List<Factor> topFactors, List<Factor> factors,
                         Notice notice) {
    }

    /**
     * @param contribution efeito em log-odds (positivo aumenta a propensão)
     * @param points       pontos do scorecard (nulo na regressão)
     * @param effect       INCREASES, DECREASES ou NEUTRAL
     * @param explanation  texto para o operador ou titular
     */
    record Factor(String feature, String label, Double value, BigDecimal contribution, Integer points, String effect,
                  String explanation) {
    }

    /**
     * @param purpose    finalidade e limites de uso
     * @param legalBasis base legal registrada
     * @param baseline   referência das contribuições
     */
    record Notice(String purpose, String legalBasis, String baseline) {
    }

    record HistoryEntry(LocalDate referenceDate, int score, String band, UUID modelId, Boolean paid) {
    }

    /**
     * @param origin           DATA_SUBJECT_REQUEST, OPERATION_REQUEST ou QUALITY_CHECK
     * @param requestReference id da solicitação do titular no compliance (opcional)
     */
    record ReviewCommand(String origin, UUID requestReference) {
    }

    record ReviewView(UUID id, UUID caseId, String origin, UUID requestReference, Integer previousScore,
                      String previousBand, UUID previousModelId, int newScore, String newBand, UUID newModelId,
                      UUID reviewedBy, Instant reviewedAt, CaseScoreView score) {
    }
}
