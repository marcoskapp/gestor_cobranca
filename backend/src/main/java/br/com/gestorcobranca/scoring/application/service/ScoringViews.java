package br.com.gestorcobranca.scoring.application.service;

import br.com.gestorcobranca.scoring.application.port.in.CaseScoreUseCases.CaseScoreView;
import br.com.gestorcobranca.scoring.application.port.in.CaseScoreUseCases.Factor;
import br.com.gestorcobranca.scoring.application.port.in.CaseScoreUseCases.Notice;
import br.com.gestorcobranca.scoring.application.port.in.ModelUseCases.DecisionView;
import br.com.gestorcobranca.scoring.application.port.in.ModelUseCases.ModelView;
import br.com.gestorcobranca.scoring.application.port.in.ScoringOperations.MonitoringView;
import br.com.gestorcobranca.scoring.application.port.in.ScoringOperations.RunView;
import br.com.gestorcobranca.scoring.application.port.in.ScoringSettingsUseCases.SettingsView;
import br.com.gestorcobranca.scoring.domain.model.CaseScore;
import br.com.gestorcobranca.scoring.domain.model.Evaluation;
import br.com.gestorcobranca.scoring.domain.model.Feature;
import br.com.gestorcobranca.scoring.domain.model.ModelDecision;
import br.com.gestorcobranca.scoring.domain.model.ModelKind;
import br.com.gestorcobranca.scoring.domain.model.MonitoringReport;
import br.com.gestorcobranca.scoring.domain.model.ScoringModel;
import br.com.gestorcobranca.scoring.domain.model.ScoringRun;
import br.com.gestorcobranca.scoring.domain.model.ScoringSettings;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.json.JsonCodec;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Conversão de entidades em views da API e textos da explicação do score. */
@Component
class ScoringViews {

    static final String PURPOSE = "Score usado somente para priorizar o atendimento (ordem das filas e seleção de "
            + "público de campanhas). Não é usado para negar atendimento nem para definir condições de negociação.";
    private static final TypeReference<List<Evaluation.Contribution>> CONTRIBUTIONS = new TypeReference<>() {
    };
    private static final TypeReference<List<Object>> LIST = new TypeReference<>() {
    };
    private static final double NEUTRAL = 0.005;
    private static final int TOP = 5;

    private final JsonCodec json;

    ScoringViews(JsonCodec json) {
        this.json = json;
    }

    CaseScoreView caseScore(CaseScore s, ScoringSettings settings, int outcomeDays) {
        List<Evaluation.Contribution> contributions = json.fromJson(s.getContributions(), CONTRIBUTIONS);
        List<Factor> factors = contributions.stream().map(ScoringViews::factor).toList();
        List<Factor> top = contributions.stream().filter(c -> Math.abs(c.logOdds()) >= NEUTRAL)
                .sorted(Comparator.comparingDouble((Evaluation.Contribution c) -> -Math.abs(c.logOdds()))
                        .thenComparing(c -> c.feature().ordinal()))
                .limit(TOP).map(ScoringViews::factor).toList();
        String baseline = s.getModelKind() == ModelKind.SCORECARD
                ? "Contribuições em pontos do scorecard (a cada 20 pontos a chance de pagamento dobra)."
                : "Contribuições em relação a um caso médio do histórico da assessoria.";
        String basis = settings == null || settings.getLegalBasis() == null ? null : settings.getLegalBasis().name();
        return new CaseScoreView(s.getCaseId(), s.getCreditorId(), s.getPortfolioId(), s.getScore(), s.getBand(),
                s.getProbability(), outcomeDays, s.getModelId(), s.getModelVersion(), s.getModelKind().name(),
                s.getReferenceDate(), s.getCalculatedAt(), s.getTrigger().name(), top, factors,
                new Notice(PURPOSE, basis, baseline));
    }

    static Factor factor(Evaluation.Contribution c) {
        double v = c.logOdds();
        String effect = Math.abs(v) < NEUTRAL ? "NEUTRAL" : v > 0 ? "INCREASES" : "DECREASES";
        String text = c.feature().label() + ": " + display(c.feature(), c.value()) + " — "
                + switch (effect) {
                    case "INCREASES" -> "aumenta a propensão de pagamento";
                    case "DECREASES" -> "reduz a propensão de pagamento";
                    default -> "sem efeito relevante";
                };
        return new Factor(c.feature().name(), c.feature().label(), c.value(),
                BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_EVEN), c.points(), effect, text);
    }

    static String display(Feature f, Double value) {
        if (value == null) {
            return "sem registro";
        }
        return switch (f) {
            case OPEN_BALANCE -> NumberFormat.getCurrencyInstance(Locale.forLanguageTag("pt-BR")).format(value);
            case DAYS_OVERDUE, CASE_AGE_DAYS, DAYS_SINCE_EFFECTIVE_CONTACT, DAYS_SINCE_LAST_PAYMENT ->
                    Math.round(value) + " dia(s)";
            case ACTIVE_AGREEMENT, PENDING_PROMISE -> value >= 1 ? "sim" : "não";
            case PORTFOLIO_RECOVERY_RATE -> BigDecimal.valueOf(value * 100).setScale(1, RoundingMode.HALF_EVEN) + "%";
            default -> String.valueOf(Math.round(value));
        };
    }

    ModelView model(ScoringModel m) {
        List<String> reasons = m.getFailureReasons() == null || m.getFailureReasons().isBlank() ? List.of()
                : Arrays.asList(m.getFailureReasons().split(","));
        return new ModelView(m.getId(), m.getModelVersion(), m.getKind().name(), m.getStatus().name(),
                m.getDescription(), m.getOutcomeDays(), json.toMap(m.getDefinition()),
                List.of(m.bands().a(), m.bands().b(), m.bands().c(), m.bands().d()), m.getPaidShare(),
                m.getTrainingFrom(), m.getTrainingTo(), m.getValidationFrom(), m.getValidationTo(), m.getTrainingRows(),
                m.getTrainingPositives(), m.getValidationRows(), m.getValidationPositives(), json.toMap(m.getMetrics()),
                reasons, m.getTrainedBy(), m.getTrainedAt(), m.getPromotedAt(), m.getPromotedBy(), m.getEndedAt());
    }

    DecisionView decision(ModelDecision d) {
        return new DecisionView(d.getId(), d.getModelId(), d.getDecision().name(), d.getPreviousChampionId(),
                d.getJustification(), json.toMap(d.getComparison()), d.getDecidedBy(), d.getDecidedAt());
    }

    RunView run(ScoringRun r) {
        return new RunView(r.getId(), r.getKind().name(), r.getTrigger().name(), r.getStatus().name(),
                r.getReferenceDate(), r.getReferenceAt(), r.isSnapshot(), r.getOutcomeDays(), r.getChampionModelId(),
                r.getChallengerModelId(), r.getResultModelId(), r.getScored(), r.getSkipped(), r.getPublished(),
                r.getBandChanges(), r.getFailures(), json.toMap(r.getDetails()), r.getRequestedBy(), r.getStartedAt(),
                r.getFinishedAt(), r.getCohortMeasuredAt());
    }

    MonitoringView monitoring(MonitoringReport m) {
        return new MonitoringView(m.getId(), m.getRunId(), m.getModelId(), m.getReferenceDate(), m.getStatus().name(),
                m.getPopulation(), m.getScorePsi(), json.toMap(m.getFeaturePsi()), json.fromJson(m.getRealized(), LIST),
                json.fromJson(m.getAlerts(), LIST));
    }

    static SettingsView settings(ScoringSettings s) {
        ScoringSettings.Parameters p = s.parameters();
        return new SettingsView(p.outcomeDays(), p.minCases(), p.minPositives(), p.minPartitionPositives(),
                p.validationShare(), p.l2Lambda(), p.minAuc(), p.trainingWindowDays(), p.snapshotIntervalDays(),
                p.publishMinDelta(), p.psiWarning(), p.psiAlert(),
                List.of(p.bands().a(), p.bands().b(), p.bands().c(), p.bands().d()), p.defaultPaidShare(), p.autoTrain(),
                s.getLegalBasis() == null ? null : s.getLegalBasis().name(), s.getLegalBasisReference(),
                s.getLegalBasisRegisteredAt(), s.getLegalBasisRegisteredBy(), s.legalBasisRegistered());
    }

    static <E extends Enum<E>> E parse(Class<E> type, String value, String code, String label) {
        if (value == null || value.isBlank()) {
            throw new BusinessRuleException(code, label + " obrigatório");
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessRuleException(code, label + " inválido: " + value);
        }
    }

    Map<String, Object> map(String jsonText) {
        return json.toMap(jsonText);
    }
}
