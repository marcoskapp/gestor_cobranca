package br.com.gestorcobranca.scoring.application;

import br.com.gestorcobranca.scoring.domain.model.BandCutoffs;
import br.com.gestorcobranca.scoring.domain.model.ScoringSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/**
 * Parâmetros do módulo scoring ({@code app.scoring.*}). Os {@code defaults} viram os parâmetros
 * iniciais da assessoria ({@code PUT /api/v1/scoring/settings} os altera por tenant).
 *
 * @param batchSize            casos por job da escoragem em lote
 * @param dailyCron            escoragem diária (fuso de operação)
 * @param maintenanceCron      apuração de desfechos, recuperado × esperado e retenção
 * @param trainingCron         treino automático (se habilitado na assessoria); promoção é sempre manual
 * @param factLookbackDays     fatos considerados nas features (dias para trás)
 * @param factRetentionDays    retenção dos fatos
 * @param snapshotRetentionDays retenção dos snapshots apurados
 * @param outcomeGraceDays     carência após o fim da janela antes de apurar (confirmações tardias)
 * @param rescoreDelay         atraso do recálculo por evento (agrupa fatos próximos do mesmo caso)
 * @param maxTrainingRows      observações mais recentes usadas no treino
 */
@ConfigurationProperties(prefix = "app.scoring")
public record ScoringProperties(Defaults defaults, Integer batchSize, String dailyCron, String maintenanceCron,
                                String trainingCron, Integer factLookbackDays, Integer factRetentionDays,
                                Integer snapshotRetentionDays, Integer outcomeGraceDays, Duration rescoreDelay,
                                Integer maxTrainingRows) {

    public ScoringProperties {
        defaults = defaults == null ? new Defaults(null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null) : defaults;
        batchSize = batchSize == null ? 200 : batchSize;
        dailyCron = dailyCron == null ? "0 30 2 * * *" : dailyCron;
        maintenanceCron = maintenanceCron == null ? "0 15 4 * * *" : maintenanceCron;
        trainingCron = trainingCron == null ? "0 0 5 1 * *" : trainingCron;
        factLookbackDays = factLookbackDays == null ? 730 : factLookbackDays;
        factRetentionDays = factRetentionDays == null ? 1095 : factRetentionDays;
        snapshotRetentionDays = snapshotRetentionDays == null ? 730 : snapshotRetentionDays;
        outcomeGraceDays = outcomeGraceDays == null ? 5 : outcomeGraceDays;
        rescoreDelay = rescoreDelay == null ? Duration.ofMinutes(5) : rescoreDelay;
        maxTrainingRows = maxTrainingRows == null ? 200_000 : maxTrainingRows;
    }

    public record Defaults(Integer outcomeDays, Integer minCases, Integer minPositives, Integer minPartitionPositives,
                           BigDecimal validationShare, BigDecimal l2Lambda, BigDecimal minAuc,
                           Integer trainingWindowDays, Integer snapshotIntervalDays, Integer publishMinDelta,
                           BigDecimal psiWarning, BigDecimal psiAlert, List<Integer> bandCutoffs,
                           BigDecimal defaultPaidShare, Boolean autoTrain) {

        public ScoringSettings.Parameters toParameters() {
            List<Integer> bands = bandCutoffs == null || bandCutoffs.size() != 4 ? List.of(400, 250, 150, 75) : bandCutoffs;
            return new ScoringSettings.Parameters(or(outcomeDays, 30), or(minCases, 500), or(minPositives, 50),
                    or(minPartitionPositives, 10), or(validationShare, "0.30"), or(l2Lambda, "1.0"), or(minAuc, "0.60"),
                    or(trainingWindowDays, 365), or(snapshotIntervalDays, 7), or(publishMinDelta, 10),
                    or(psiWarning, "0.10"), or(psiAlert, "0.25"),
                    new BandCutoffs(bands.get(0), bands.get(1), bands.get(2), bands.get(3)),
                    or(defaultPaidShare, "0.30"), autoTrain == null || autoTrain);
        }

        private static int or(Integer v, int d) {
            return v == null ? d : v;
        }

        private static BigDecimal or(BigDecimal v, String d) {
            return v == null ? new BigDecimal(d) : v;
        }
    }
}
