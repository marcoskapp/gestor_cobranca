package br.com.gestorcobranca.incentive.domain.service;

import br.com.gestorcobranca.incentive.domain.model.tier.PersonSample;
import br.com.gestorcobranca.incentive.domain.model.tier.TierProposal;
import br.com.gestorcobranca.incentive.domain.model.tier.TierSettings;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Classificação do negociador (distribuicao-por-desempenho.md §3), função pura:
 * <ol>
 *   <li><b>Quartil de qualidade</b>: posição da nota média da monitoria entre quem tem o mínimo de avaliações; falha
 *       crítica na janela ou média abaixo do portão → no máximo Q2.</li>
 *   <li><b>Quartil de eficiência</b>: posição do potencial de reversão (Σ realizado ÷ Σ esperado das safras) entre quem
 *       tem o mínimo de casos e valor esperado maior que zero.</li>
 *   <li><b>Nível</b>: célula da matriz; faltando um dos quartis, o nível padrão.</li>
 * </ol>
 * Quartis pela <b>posição percentil</b> (meio do posto: {@code (abaixo + iguais/2) ÷ n}): Q1 abaixo de 25%, Q2 abaixo
 * de 50%, Q3 abaixo de 75%, Q4 do restante. Empatados ficam no mesmo quartil; grupos com menos de 4 pessoas não forçam
 * quartis vazios nem cheios (uma pessoa sozinha, ou todos empatados, ficam em Q3).
 */
public final class TierClassifier {

    private TierClassifier() {
    }

    public static List<TierProposal> classify(Collection<PersonSample> samples, TierSettings settings) {
        Map<Object, BigDecimal> quality = new HashMap<>();
        Map<Object, BigDecimal> efficiency = new HashMap<>();
        Map<Object, BigDecimal> ratios = new HashMap<>();
        for (PersonSample s : samples) {
            if (s.evaluations() >= settings.minEvaluations() && s.qualityAverage() != null) {
                quality.put(s.userId(), s.qualityAverage());
            }
            BigDecimal ratio = ratio(s);
            if (ratio != null) {
                ratios.put(s.userId(), ratio);
                if (s.cases() >= settings.minCases()) {
                    efficiency.put(s.userId(), ratio);
                }
            }
        }
        Map<Object, Integer> qualityQuartiles = quartiles(quality);
        Map<Object, Integer> efficiencyQuartiles = quartiles(efficiency);
        List<TierProposal> result = new ArrayList<>();
        for (PersonSample s : samples) {
            boolean gateFailed = s.criticalFailure()
                    || (s.qualityAverage() != null && s.qualityAverage().compareTo(settings.qualityGate()) < 0);
            Integer q = qualityQuartiles.get(s.userId());
            if (q != null && gateFailed) {
                q = Math.min(q, 2);
            }
            Integer e = efficiencyQuartiles.get(s.userId());
            var tier = q == null || e == null ? settings.defaultTier() : settings.matrix().tierFor(q, e);
            result.add(new TierProposal(s.userId(), q, e, gateFailed, s.evaluations(), s.qualityAverage(), s.cases(),
                    ratios.get(s.userId()), tier));
        }
        return result;
    }

    /** Quartil (1–4) de cada chave pela posição percentil (meio do posto); empates no mesmo quartil. */
    public static <K> Map<K, Integer> quartiles(Map<K, BigDecimal> values) {
        Map<K, Integer> result = new HashMap<>();
        int n = values.size();
        if (n == 0) {
            return result;
        }
        Collection<BigDecimal> all = values.values();
        for (Map.Entry<K, BigDecimal> entry : values.entrySet()) {
            long below = all.stream().filter(v -> v.compareTo(entry.getValue()) < 0).count();
            long equal = all.stream().filter(v -> v.compareTo(entry.getValue()) == 0).count();
            // percentil p = (abaixo + iguais/2) ÷ n; p < k/4  ⇔  2 × (2·abaixo + iguais) < n·k (sem arredondamento)
            long twice = 2 * (2 * below + equal);
            int quartile = twice < n ? 1 : twice < 2L * n ? 2 : twice < 3L * n ? 3 : 4;
            result.put(entry.getKey(), quartile);
        }
        return result;
    }

    /** Σ realizado ÷ Σ esperado (4 casas); nulo sem valor esperado. */
    static BigDecimal ratio(PersonSample s) {
        if (s.expectedAmount() == null || s.expectedAmount().signum() <= 0) {
            return null;
        }
        BigDecimal realized = s.realizedAmount() == null ? BigDecimal.ZERO : s.realizedAmount();
        return realized.divide(s.expectedAmount(), 4, RoundingMode.HALF_EVEN);
    }
}
