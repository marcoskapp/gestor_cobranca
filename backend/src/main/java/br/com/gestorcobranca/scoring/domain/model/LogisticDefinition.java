package br.com.gestorcobranca.scoring.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;

import java.util.ArrayList;
import java.util.List;

/**
 * Regressão logística treinada: {@code logit = intercept + Σ βⱼ · zⱼ}, com
 * {@code zⱼ = (transformadaⱼ(x) − médiaⱼ) / desvioⱼ}. Valor ausente de feature IDENTITY é imputado
 * pela média (z = 0). As contribuições βⱼ·zⱼ são relativas ao caso médio do treino.
 */
public record LogisticDefinition(double intercept, List<Term> terms) implements ScoringFunction {

    public LogisticDefinition {
        Guard.require(terms != null && !terms.isEmpty(), "logistic.terms_required", "Coeficientes obrigatórios");
        terms = List.copyOf(terms);
        Guard.require(Double.isFinite(intercept), "logistic.invalid", "Intercepto inválido");
        for (Term t : terms) {
            Guard.require(t.feature() != null && Double.isFinite(t.coefficient()) && Double.isFinite(t.mean())
                    && t.std() > 0 && Double.isFinite(t.std()), "logistic.invalid", "Coeficiente inválido");
        }
    }

    public record Term(Feature feature, double coefficient, double mean, double std) {

        double standardized(Double raw) {
            Double t = feature.transformed(raw);
            return t == null ? 0.0 : (t - mean) / std;
        }
    }

    @Override
    public Evaluation evaluate(FeatureVector features) {
        double logit = intercept;
        List<Evaluation.Contribution> contributions = new ArrayList<>();
        for (Term t : terms) {
            Double raw = features.get(t.feature());
            double c = t.coefficient() * t.standardized(raw);
            logit += c;
            contributions.add(new Evaluation.Contribution(t.feature(), raw, c, null));
        }
        contributions.sort((a, b) -> Integer.compare(a.feature().ordinal(), b.feature().ordinal()));
        return new Evaluation(Evaluation.sigmoid(logit), logit, contributions);
    }
}
