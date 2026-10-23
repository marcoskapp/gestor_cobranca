package br.com.gestorcobranca.incentive.domain.service;

import br.com.gestorcobranca.incentive.domain.model.Segment;
import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules;
import br.com.gestorcobranca.shared.domain.Money;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Entradas do motor de cálculo para um negociador num período — tudo o que o cálculo usa, para
 * que o extrato seja reproduzível a partir da memória gravada.
 *
 * @param volumes                 créditos líquidos liberados no período por segmento (acréscimos
 *                                menos compensações), já ponderados pelo peso de crédito
 * @param firstInstallmentWeight  soma das frações de crédito dos acordos cuja 1ª parcela foi paga
 *                                no período (para o valor fixo por acordo)
 * @param workedDays              dias com tempo logado no período
 * @param goal                    meta e realizado na unidade da métrica (null = sem meta)
 * @param poolShares              participações em pools de equipe
 */
public record EngineInput(PlanRules rules, List<SegmentVolume> volumes, BigDecimal firstInstallmentWeight, int workedDays,
                          GoalInput goal, QualityInput quality, List<PoolShare> poolShares) {

    public EngineInput {
        Objects.requireNonNull(rules, "rules");
        volumes = volumes == null ? List.of() : List.copyOf(volumes);
        firstInstallmentWeight = firstInstallmentWeight == null ? BigDecimal.ZERO : firstInstallmentWeight;
        quality = quality == null ? QualityInput.NONE : quality;
        poolShares = poolShares == null ? List.of() : List.copyOf(poolShares);
    }

    public record SegmentVolume(Segment segment, Money amount) {
    }

    /**
     * @param target meta (R$ para recuperado; % para taxa de recuperação)
     * @param actual realizado na mesma unidade (individual ou da equipe, conforme o escopo)
     */
    public record GoalInput(BigDecimal target, BigDecimal actual) {
    }

    /**
     * @param safraAgreements     acordos formalizados na safra avaliada pelo deflator de quebra
     * @param safraEarlyBreaks    desses, quebrados dentro da janela
     * @param envelopeSample      acordos formalizados no período com uso de envelope informado
     * @param envelopeUsageAverage uso médio do envelope (0–100)
     * @param evaluations         monitorias no período
     * @param qaAverage           nota média de monitoria (0–10), null sem avaliações
     * @param severeViolations    violações graves de compliance confirmadas no período
     */
    public record QualityInput(int safraAgreements, int safraEarlyBreaks, int envelopeSample, BigDecimal envelopeUsageAverage,
                               int evaluations, BigDecimal qaAverage, int severeViolations) {

        public static final QualityInput NONE = new QualityInput(0, 0, 0, null, 0, null, 0);
    }

    /**
     * @param teamRecovered recuperado da equipe no período (base do pool)
     * @param divisor       número de pessoas que dividem o pool (1 = só o supervisor)
     */
    public record PoolShare(UUID teamId, Money teamRecovered, int divisor) {
    }
}
