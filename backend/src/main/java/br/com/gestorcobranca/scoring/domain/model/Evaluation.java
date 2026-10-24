package br.com.gestorcobranca.scoring.domain.model;

import java.util.Comparator;
import java.util.List;

/**
 * Resultado de um modelo para um caso.
 *
 * @param probability   probabilidade estimada de pagamento confirmado na janela de desfecho
 * @param logit         log-odds correspondente
 * @param contributions contribuição de cada feature em log-odds (positivo aumenta a propensão), na
 *                      ordem do catálogo; a referência é o caso médio do treino (regressão) ou zero
 *                      pontos (scorecard)
 */
public record Evaluation(double probability, double logit, List<Contribution> contributions) {

    public Evaluation {
        contributions = List.copyOf(contributions);
    }

    /** Score público 0–1000 (probabilidade × 1000, arredondada). */
    public int score() {
        return (int) Math.round(Math.min(1.0, Math.max(0.0, probability)) * 1000.0);
    }

    /** Fatores de maior efeito absoluto (explicação), desempate pela ordem do catálogo. */
    public List<Contribution> topFactors(int limit) {
        return contributions.stream().filter(c -> c.logOdds() != 0.0)
                .sorted(Comparator.comparingDouble((Contribution c) -> -Math.abs(c.logOdds()))
                        .thenComparing(c -> c.feature().ordinal()))
                .limit(limit).toList();
    }

    /**
     * @param value  valor bruto da feature (null = ausente)
     * @param points pontos do scorecard (null na regressão)
     */
    public record Contribution(Feature feature, Double value, double logOdds, Integer points) {
    }

    static double sigmoid(double x) {
        return x >= 0 ? 1.0 / (1.0 + Math.exp(-x)) : Math.exp(x) / (1.0 + Math.exp(x));
    }
}
