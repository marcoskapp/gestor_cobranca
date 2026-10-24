package br.com.gestorcobranca.scoring.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Scorecard por pontos (transparente, padrão quando não há histórico): pontos base + pontos da faixa
 * de cada regra. A escala segue o padrão de mercado de "pontos para dobrar a chance" (PDO): cada
 * {@code pdo} pontos dobram a chance (odds) de pagamento, e {@code anchorPoints} pontos equivalem a
 * {@code anchorOdds}. Assim os pontos viram probabilidade de forma explícita e auditável.
 *
 * @param basePoints   pontos iniciais de todo caso
 * @param pdo          pontos que dobram a chance (> 0)
 * @param anchorPoints pontos de referência
 * @param anchorOdds   chance (p ÷ (1 − p)) nos pontos de referência (ex.: 1/9 = 10%)
 */
public record ScorecardDefinition(int basePoints, int pdo, int anchorPoints, double anchorOdds, List<Rule> rules)
        implements ScoringFunction {

    private static final int MAX_POINTS = 500;

    public ScorecardDefinition {
        Guard.require(pdo > 0 && pdo <= 200, "scorecard.pdo_invalid", "PDO deve estar entre 1 e 200");
        Guard.require(anchorOdds > 0 && anchorOdds < 1000, "scorecard.anchor_invalid", "Chance de referência inválida");
        Guard.require(Math.abs(basePoints) <= 5000 && Math.abs(anchorPoints) <= 5000, "scorecard.points_invalid",
                "Pontos fora do limite");
        Guard.require(rules != null && !rules.isEmpty(), "scorecard.rules_required", "Informe ao menos uma regra");
        rules = List.copyOf(rules);
        Set<Feature> seen = EnumSet.noneOf(Feature.class);
        for (Rule r : rules) {
            Guard.require(r != null && r.feature() != null, "scorecard.feature_required", "Feature obrigatória");
            Guard.require(seen.add(r.feature()), "scorecard.feature_duplicated", "Feature repetida: " + r.feature());
        }
    }

    /**
     * @param missingPoints pontos quando o valor está ausente (ex.: nunca houve contato)
     */
    public record Rule(Feature feature, List<Bin> bins, int missingPoints) {

        public Rule {
            Guard.require(bins != null, "scorecard.bins_required", "Faixas obrigatórias");
            bins = List.copyOf(bins);
            Guard.require(Math.abs(missingPoints) <= MAX_POINTS, "scorecard.points_invalid", "Pontos fora do limite");
            List<Bin> sorted = new ArrayList<>(bins);
            sorted.sort((a, b) -> Double.compare(a.lower(), b.lower()));
            for (int i = 0; i < sorted.size(); i++) {
                Bin b = sorted.get(i);
                Guard.require(b.lower() < b.upper(), "scorecard.bin_invalid", "Faixa vazia em " + feature);
                Guard.require(Math.abs(b.points()) <= MAX_POINTS, "scorecard.points_invalid", "Pontos fora do limite");
                if (i > 0) {
                    Guard.require(sorted.get(i - 1).upper() <= b.lower(), "scorecard.bins_overlap",
                            "Faixas sobrepostas em " + feature);
                }
            }
        }

        int pointsFor(Double value) {
            if (value == null) {
                return missingPoints;
            }
            for (Bin b : bins) {
                if (b.contains(value)) {
                    return b.points();
                }
            }
            return 0;
        }
    }

    /**
     * Faixa [{@code from}, {@code to}); {@code null} = sem limite.
     */
    public record Bin(Double from, Double to, int points) {

        double lower() {
            return from == null ? Double.NEGATIVE_INFINITY : from;
        }

        double upper() {
            return to == null ? Double.POSITIVE_INFINITY : to;
        }

        boolean contains(double v) {
            return v >= lower() && v < upper();
        }
    }

    /** Log-odds equivalente a um ponto. */
    public double logOddsPerPoint() {
        return Math.log(2.0) / pdo;
    }

    /** Total de pontos do caso. */
    public int points(FeatureVector features) {
        int total = basePoints;
        for (Rule r : rules) {
            total += r.pointsFor(features.get(r.feature()));
        }
        return total;
    }

    @Override
    public Evaluation evaluate(FeatureVector features) {
        double perPoint = logOddsPerPoint();
        List<Evaluation.Contribution> contributions = new ArrayList<>();
        int total = basePoints;
        for (Rule r : rules) {
            int points = r.pointsFor(features.get(r.feature()));
            total += points;
            contributions.add(new Evaluation.Contribution(r.feature(), features.get(r.feature()), points * perPoint,
                    points));
        }
        contributions.sort((a, b) -> Integer.compare(a.feature().ordinal(), b.feature().ordinal()));
        double logit = Math.log(anchorOdds) + (total - anchorPoints) * perPoint;
        return new Evaluation(Evaluation.sigmoid(logit), logit, contributions);
    }

    /**
     * Scorecard padrão (pesos de especialista, documentados em {@code docs/negocio/score-propensao.md}
     * §5): 500 pontos = chance 1:9 (10%), 20 pontos dobram a chance.
     */
    public static ScorecardDefinition standard() {
        return new ScorecardDefinition(500, 20, 500, 1.0 / 9.0, List.of(
                new Rule(Feature.DAYS_OVERDUE, List.of(new Bin(null, 31.0, 30), new Bin(31.0, 91.0, 15),
                        new Bin(91.0, 181.0, 0), new Bin(181.0, 361.0, -15), new Bin(361.0, null, -30)), 0),
                new Rule(Feature.OPEN_BALANCE, List.of(new Bin(null, 500.0, 10), new Bin(500.0, 1000.0, 5),
                        new Bin(1000.0, 5000.0, 0), new Bin(5000.0, 20000.0, -5), new Bin(20000.0, null, -10)), 0),
                new Rule(Feature.EFFECTIVE_CONTACTS_90D, List.of(new Bin(null, 1.0, -10), new Bin(1.0, 3.0, 10),
                        new Bin(3.0, null, 15)), 0),
                new Rule(Feature.DAYS_SINCE_EFFECTIVE_CONTACT, List.of(new Bin(null, 8.0, 20), new Bin(8.0, 31.0, 10),
                        new Bin(31.0, 91.0, 0), new Bin(91.0, null, -5)), -10),
                new Rule(Feature.RESPONSIVE_CHANNELS_90D, List.of(new Bin(1.0, null, 10)), 0),
                new Rule(Feature.PROMISES_KEPT, List.of(new Bin(1.0, null, 20)), 0),
                new Rule(Feature.PROMISES_BROKEN, List.of(new Bin(1.0, 3.0, -10), new Bin(3.0, null, -20)), 0),
                new Rule(Feature.PRIOR_AGREEMENTS, List.of(new Bin(1.0, null, 5)), 0),
                new Rule(Feature.BROKEN_AGREEMENTS, List.of(new Bin(1.0, null, -15)), 0),
                new Rule(Feature.PARTIAL_PAYMENTS_365D, List.of(new Bin(1.0, null, 20)), 0),
                new Rule(Feature.DAYS_SINCE_LAST_PAYMENT, List.of(new Bin(null, 31.0, 15), new Bin(31.0, 91.0, 5)), 0),
                new Rule(Feature.ACTIVE_AGREEMENT, List.of(new Bin(1.0, null, 40)), 0),
                new Rule(Feature.PENDING_PROMISE, List.of(new Bin(1.0, null, 20)), 0)));
    }
}
