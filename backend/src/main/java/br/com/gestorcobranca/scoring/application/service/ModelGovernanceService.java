package br.com.gestorcobranca.scoring.application.service;

import br.com.gestorcobranca.scoring.application.port.in.ModelUseCases;
import br.com.gestorcobranca.scoring.application.port.out.SnapshotStore;
import br.com.gestorcobranca.scoring.domain.model.Feature;
import br.com.gestorcobranca.scoring.domain.model.ModelDecision;
import br.com.gestorcobranca.scoring.domain.model.ModelKind;
import br.com.gestorcobranca.scoring.domain.model.ModelStatus;
import br.com.gestorcobranca.scoring.domain.model.MonitoringReport;
import br.com.gestorcobranca.scoring.domain.model.ScorecardDefinition;
import br.com.gestorcobranca.scoring.domain.model.ScoringModel;
import br.com.gestorcobranca.scoring.domain.model.ScoringRun;
import br.com.gestorcobranca.scoring.domain.model.ScoringSettings;
import br.com.gestorcobranca.scoring.domain.model.TrainingRow;
import br.com.gestorcobranca.scoring.domain.repository.ModelDecisionRepository;
import br.com.gestorcobranca.scoring.domain.repository.MonitoringReportRepository;
import br.com.gestorcobranca.scoring.domain.repository.ScoringModelRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Governança dos modelos: catálogo, treino sob pedido, scorecards configurados, promoção manual do
 * desafiante a campeão e rejeição — sempre com justificativa, comparação campeão × desafiante
 * registrada e auditoria. Modelos são da assessoria inteira: exige acesso a todos os credores.
 */
@Service
@Transactional
class ModelGovernanceService implements ModelUseCases {

    /** O que nunca entra no score (documentado em docs/negocio/score-propensao.md §4). */
    static final List<String> NEVER_USED = List.of("nome", "documento (CPF/CNPJ)", "data de nascimento e idade",
            "gênero e estado civil", "raça, cor ou etnia", "religião, opinião política, filiação sindical",
            "saúde, deficiência ou dados genéticos/biométricos", "endereço, CEP, cidade, UF, DDD ou outra localização",
            "renda presumida ou profissão", "tipo de pessoa (física/jurídica)", "domínio de e-mail ou operadora",
            "conteúdo de mensagens e observações livres", "dados de terceiros (coobrigados, familiares)");
    private static final int BACKTEST_DAYS = 180;
    private static final int BACKTEST_ROWS = 50_000;

    private final ScoringModelRepository models;
    private final ModelDecisionRepository decisions;
    private final MonitoringReportRepository reports;
    private final SnapshotStore snapshots;
    private final ModelRegistry registry;
    private final SettingsProvider settingsProvider;
    private final TrainingService training;
    private final ScoringViews views;
    private final JsonCodec json;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final ScoringMetrics metrics;
    private final Clock clock;

    ModelGovernanceService(ScoringModelRepository models, ModelDecisionRepository decisions,
                           MonitoringReportRepository reports, SnapshotStore snapshots, ModelRegistry registry,
                           SettingsProvider settingsProvider, TrainingService training, ScoringViews views,
                           JsonCodec json, AccessScope accessScope, CurrentActor currentActor, AuditTrail auditTrail,
                           BusinessEventLogger businessEvents, ScoringMetrics metrics, Clock clock) {
        this.models = models;
        this.decisions = decisions;
        this.reports = reports;
        this.snapshots = snapshots;
        this.registry = registry;
        this.settingsProvider = settingsProvider;
        this.training = training;
        this.views = views;
        this.json = json;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ModelView> list() {
        accessScope.requireAllCreditors("scoring_model");
        return models.findAllByOrderByModelVersionDesc().stream().map(views::model).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ModelView get(UUID modelId) {
        accessScope.requireAllCreditors("scoring_model");
        return views.model(load(modelId));
    }

    @Override
    public TrainingRequest train() {
        accessScope.requireAllCreditors("scoring_model");
        ScoringRun run = training.request(ScoringRun.Trigger.MANUAL, currentActor.actorId());
        return new TrainingRequest(run.getId(), run.getStatus().name());
    }

    @Override
    public ModelView createScorecard(ScorecardCommand command) {
        accessScope.requireAllCreditors("scoring_model");
        Guard.requireNonNull(command, "scoring.scorecard.required", "Scorecard obrigatório");
        ScorecardDefinition definition = definition(command);
        ScoringSettings settings = settingsProvider.current();
        Instant now = Instant.now(clock);
        LocalDate today = LocalDate.ofInstant(now, TimeConfig.BUSINESS_ZONE);
        List<TrainingRow> history = snapshots.trainingRows(today.minusDays(BACKTEST_DAYS), today, BACKTEST_ROWS);
        Map<String, Object> metricsJson = new LinkedHashMap<>();
        metricsJson.put("backtest", true);
        metricsJson.put("backtestDays", BACKTEST_DAYS);
        if (!history.isEmpty()) {
            metricsJson.put("validation", TrainingService.evaluate(definition, history, settings.bands()));
            registry.champion().ifPresent(champion -> metricsJson.put("championOnValidation",
                    TrainingService.evaluate(registry.function(champion), history, champion.bands())));
        }
        registry.lockModels();
        registry.challenger().ifPresent(previous -> {
            previous.supersede(now);
            models.saveAndFlush(previous);
        });
        ScoringModel model = models.save(new ScoringModel(registry.nextVersion(), ModelKind.SCORECARD,
                ModelStatus.CHALLENGER, trim(command.description()), settings.getOutcomeDays(), json.toJson(definition),
                settings.bands(), settings.getDefaultPaidShare(), null, json.toJson(metricsJson), null,
                history.isEmpty() ? null : json.toJson(TrainingService.referenceDistribution(definition, history)),
                currentActor.actorId(), now));
        auditTrail.record(AuditEntry.of("scoring_model.scorecard_created", "scoring_model", model.getId())
                .created("modelVersion", model.getModelVersion()).created("status", model.getStatus())
                .created("rules", definition.rules().size()).created("basePoints", definition.basePoints())
                .created("pdo", definition.pdo()).meta("backtestRows", history.size()));
        return views.model(model);
    }

    @Override
    public ModelView promote(UUID modelId, DecisionCommand command) {
        accessScope.requireAllCreditors("scoring_model");
        registry.lockModels();
        ScoringModel challenger = load(modelId);
        Guard.requireState(challenger.getStatus() == ModelStatus.CHALLENGER, "scoring.model.not_challenger",
                "Só o desafiante validado pode ser promovido");
        UUID actor = currentActor.actorId();
        if (challenger.getTrainedBy() != null && challenger.getTrainedBy().equals(actor)) {
            throw new ConflictException("scoring.model.self_promotion",
                    "Quem pediu o treino ou criou o scorecard não pode promovê-lo (segregação de funções)");
        }
        Instant now = Instant.now(clock);
        ScoringModel champion = registry.champion().orElse(null);
        Map<String, Object> comparison = comparison(challenger, champion);
        ModelDecision decision = decisions.save(new ModelDecision(challenger.getId(), ModelDecision.Decision.PROMOTE,
                champion == null ? null : champion.getId(), justification(command), json.toJson(comparison), actor, now));
        if (champion != null) {
            champion.retire(now);
            models.saveAndFlush(champion);
        }
        challenger.promote(actor, now);
        models.saveAndFlush(challenger);
        auditTrail.record(AuditEntry.of("scoring_model.promoted", "scoring_model", challenger.getId())
                .change("status", ModelStatus.CHALLENGER, ModelStatus.CHAMPION)
                .reason(decision.getJustification())
                .meta("previousChampionId", champion == null ? null : champion.getId())
                .meta("decisionId", decision.getId()));
        if (champion != null) {
            auditTrail.record(AuditEntry.of("scoring_model.retired", "scoring_model", champion.getId())
                    .change("status", ModelStatus.CHAMPION, ModelStatus.RETIRED)
                    .meta("replacedBy", challenger.getId()).meta("decisionId", decision.getId()));
        }
        log(BusinessEventTypes.SCORE_MODEL_PROMOTED, challenger, champion, comparison, decision);
        metrics.modelDecision("promote");
        return views.model(challenger);
    }

    @Override
    public ModelView reject(UUID modelId, DecisionCommand command) {
        accessScope.requireAllCreditors("scoring_model");
        registry.lockModels();
        ScoringModel challenger = load(modelId);
        Guard.requireState(challenger.getStatus() == ModelStatus.CHALLENGER, "scoring.model.not_challenger",
                "Só o desafiante pode ser rejeitado");
        Instant now = Instant.now(clock);
        ScoringModel champion = registry.champion().orElse(null);
        Map<String, Object> comparison = comparison(challenger, champion);
        ModelDecision decision = decisions.save(new ModelDecision(challenger.getId(), ModelDecision.Decision.REJECT,
                champion == null ? null : champion.getId(), justification(command), json.toJson(comparison),
                currentActor.actorId(), now));
        challenger.reject(now);
        models.save(challenger);
        auditTrail.record(AuditEntry.of("scoring_model.rejected", "scoring_model", challenger.getId())
                .change("status", ModelStatus.CHALLENGER, ModelStatus.REJECTED)
                .reason(decision.getJustification()).meta("decisionId", decision.getId()));
        log(BusinessEventTypes.SCORE_MODEL_REJECTED, challenger, champion, comparison, decision);
        metrics.modelDecision("reject");
        return views.model(challenger);
    }

    @Override
    @Transactional(readOnly = true)
    public List<DecisionView> decisions() {
        accessScope.requireAllCreditors("scoring_model");
        return decisions.findAllByOrderByDecidedAtDesc().stream().map(views::decision).toList();
    }

    @Override
    public FeatureCatalog features() {
        return new FeatureCatalog(Arrays.stream(Feature.values())
                .map(f -> new FeatureInfo(f.name(), f.label(), f.transform().name())).toList(), NEVER_USED,
                ScoringViews.PURPOSE);
    }

    /** Comparação vista por quem decide: validação do desafiante, campeão nas mesmas observações e o realizado. */
    private Map<String, Object> comparison(ScoringModel challenger, ScoringModel champion) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> challengerMetrics = views.map(challenger.getMetrics());
        out.put("challenger", Map.of("modelId", challenger.getId().toString(), "modelVersion",
                challenger.getModelVersion(), "kind", challenger.getKind().name(),
                "validation", challengerMetrics.getOrDefault("validation", Map.of())));
        out.put("championOnSameValidation", challengerMetrics.getOrDefault("championOnValidation", Map.of()));
        if (champion != null) {
            out.put("champion", Map.of("modelId", champion.getId().toString(), "modelVersion", champion.getModelVersion(),
                    "kind", champion.getKind().name()));
        }
        // desempenho realizado (sombra): último relatório de monitoramento
        reports.findAllByOrderByReferenceDateDesc(PageRequest.of(0, 1)).stream().findFirst()
                .map(MonitoringReport::getRealized).ifPresent(r -> out.put("realizedLatestReport", json.fromJson(r,
                        Object.class)));
        return out;
    }

    private void log(String type, ScoringModel model, ScoringModel champion, Map<String, Object> comparison,
                     ModelDecision decision) {
        Object validation = views.map(model.getMetrics()).get("validation");
        Object auc = validation instanceof Map<?, ?> m ? m.get("auc") : null;
        businessEvents.log(BusinessEvent.of(type).reference("scoring_model", model.getId())
                .attr("modelVersion", model.getModelVersion()).attr("kind", model.getKind().name())
                .attr("previousChampionVersion", champion == null ? null : champion.getModelVersion())
                .attr("validationAuc", auc).attr("decisionId", decision.getId())
                .attr("hasRealized", comparison.containsKey("realizedLatestReport")));
    }

    private ScoringModel load(UUID id) {
        return models.findById(id).orElseThrow(() -> new ResourceNotFoundException("scoring_model", id));
    }

    private static String justification(DecisionCommand command) {
        return command == null ? null : command.justification();
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static ScorecardDefinition definition(ScorecardCommand c) {
        Guard.require(c.rules() != null && !c.rules().isEmpty(), "scorecard.rules_required", "Informe ao menos uma regra");
        Guard.require(c.anchorOdds() != null, "scorecard.anchor_invalid", "Chance de referência obrigatória");
        List<ScorecardDefinition.Rule> rules = c.rules().stream().map(r -> {
            Feature feature = ScoringViews.parse(Feature.class, r.feature(), "scorecard.feature_invalid",
                    "Feature (somente as do catálogo)");
            List<ScorecardDefinition.Bin> bins = r.bins() == null ? List.of() : r.bins().stream()
                    .map(b -> new ScorecardDefinition.Bin(value(b.from()), value(b.to()), b.points())).toList();
            return new ScorecardDefinition.Rule(feature, bins, r.missingPoints());
        }).toList();
        return new ScorecardDefinition(c.basePoints(), c.pdo(), c.anchorPoints(), c.anchorOdds().doubleValue(), rules);
    }

    private static Double value(BigDecimal v) {
        return v == null ? null : v.doubleValue();
    }
}
