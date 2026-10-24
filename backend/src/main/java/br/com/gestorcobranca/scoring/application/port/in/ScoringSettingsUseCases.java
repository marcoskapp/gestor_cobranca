package br.com.gestorcobranca.scoring.application.port.in;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Parâmetros do score da assessoria e o registro da base legal. Leitura com {@code SCORING_VIEW};
 * alteração com {@code SCORING_MANAGE} e todos os credores. Sem base legal registrada nenhum score
 * é calculado e nenhum fato é coletado.
 */
public interface ScoringSettingsUseCases {

    SettingsView get();

    SettingsView update(SettingsCommand command);

    /**
     * @param legalBasis LEGITIMATE_INTEREST (art. 7º IX) ou CREDIT_PROTECTION (art. 7º X)
     * @param reference  referência do registro (ex.: avaliação de legítimo interesse aprovada pelo DPO)
     */
    SettingsView registerLegalBasis(LegalBasisCommand command);

    record SettingsCommand(int outcomeDays, int minCases, int minPositives, int minPartitionPositives,
                           BigDecimal validationShare, BigDecimal l2Lambda, BigDecimal minAuc, int trainingWindowDays,
                           int snapshotIntervalDays, int publishMinDelta, BigDecimal psiWarning, BigDecimal psiAlert,
                           List<Integer> bandCutoffs, BigDecimal defaultPaidShare, boolean autoTrain) {
    }

    record LegalBasisCommand(String legalBasis, String reference) {
    }

    record SettingsView(int outcomeDays, int minCases, int minPositives, int minPartitionPositives,
                        BigDecimal validationShare, BigDecimal l2Lambda, BigDecimal minAuc, int trainingWindowDays,
                        int snapshotIntervalDays, int publishMinDelta, BigDecimal psiWarning, BigDecimal psiAlert,
                        List<Integer> bandCutoffs, BigDecimal defaultPaidShare, boolean autoTrain, String legalBasis,
                        String legalBasisReference, Instant legalBasisRegisteredAt, UUID legalBasisRegisteredBy,
                        boolean scoringEnabled) {
    }
}
