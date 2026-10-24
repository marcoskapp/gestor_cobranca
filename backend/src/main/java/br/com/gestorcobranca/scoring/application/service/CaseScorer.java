package br.com.gestorcobranca.scoring.application.service;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.scoring.application.ScoringProperties;
import br.com.gestorcobranca.scoring.application.port.out.FactStore;
import br.com.gestorcobranca.scoring.application.port.out.ScoringLocks;
import br.com.gestorcobranca.scoring.application.port.out.SnapshotStore;
import br.com.gestorcobranca.scoring.application.port.out.SnapshotStore.SnapshotRow;
import br.com.gestorcobranca.scoring.domain.model.CaseFact;
import br.com.gestorcobranca.scoring.domain.model.CaseInputs;
import br.com.gestorcobranca.scoring.domain.model.CaseScore;
import br.com.gestorcobranca.scoring.domain.model.Evaluation;
import br.com.gestorcobranca.scoring.domain.model.FeatureVector;
import br.com.gestorcobranca.scoring.domain.model.ScoringFunction;
import br.com.gestorcobranca.scoring.domain.model.ScoringModel;
import br.com.gestorcobranca.scoring.domain.model.ScoringRun;
import br.com.gestorcobranca.scoring.domain.model.ScoringSettings;
import br.com.gestorcobranca.scoring.domain.repository.CaseScoreRepository;
import br.com.gestorcobranca.scoring.domain.service.FeatureCalculator;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.events.scoring.CaseScoreChanged;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Cálculo do score de um lote de casos, na transação do chamador (lote diário, sob demanda, evento ou
 * revisão): features a partir da posição do caso e dos fatos, campeão (e desafiante em sombra nos
 * snapshots), gravação do score vigente, snapshot do dia de referência, publicação de
 * {@link CaseScoreChanged} quando relevante, auditoria da mudança de faixa e evento de negócio.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
class CaseScorer {

    private static final Logger LOG = LoggerFactory.getLogger(CaseScorer.class);

    /** Casos fora da escoragem: jurídico (fluxo próprio) e encerrados. */
    private static final Set<String> NOT_SCORED = Set.of("LEGAL", "CLOSED");
    private static final String ANONYMIZED = "DEBTOR_ANONYMIZED";

    private final FactStore facts;
    private final SnapshotStore snapshots;
    private final CaseScoreRepository scores;
    private final ScoringLocks locks;
    private final ModelRegistry registry;
    private final JsonCodec json;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final ApplicationEventPublisher events;
    private final CurrentActor currentActor;
    private final ScoringMetrics metrics;
    private final ScoringProperties properties;

    CaseScorer(FactStore facts, SnapshotStore snapshots, CaseScoreRepository scores, ScoringLocks locks,
               ModelRegistry registry, JsonCodec json, AuditTrail auditTrail, BusinessEventLogger businessEvents,
               ApplicationEventPublisher events, CurrentActor currentActor, ScoringMetrics metrics,
               ScoringProperties properties) {
        this.facts = facts;
        this.snapshots = snapshots;
        this.scores = scores;
        this.locks = locks;
        this.registry = registry;
        this.json = json;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.events = events;
        this.currentActor = currentActor;
        this.metrics = metrics;
        this.properties = properties;
    }

    /**
     * Contexto fixo de um cálculo.
     *
     * @param run            escoragem em lote (snapshot, se for dia de snapshot); nulo fora do lote
     * @param portfolioRates taxa histórica por carteira (chave nula = assessoria)
     */
    record Context(ScoringSettings settings, ScoringModel champion, ScoringModel challenger, Map<UUID, Double> portfolioRates,
                   LocalDate referenceDate, Instant at, CaseScore.Trigger trigger, ScoringRun run) {
    }

    record Result(int scored, int skipped, int published, int bandChanges, int failures, List<CaseScore> scores) {
    }

    Result score(List<CaseView> cases, Context ctx) {
        List<CaseView> eligible = new ArrayList<>();
        int skipped = 0;
        for (CaseView c : cases) {
            String reason = skipReason(c);
            if (reason != null) {
                skipped++;
                metrics.skipped(reason);
            } else {
                eligible.add(c);
            }
        }
        if (eligible.isEmpty()) {
            return new Result(0, skipped, 0, 0, 0, List.of());
        }
        locks.lockCases(eligible.stream().map(CaseView::id).toList());
        Set<UUID> debtors = eligible.stream().map(CaseView::debtorId).collect(Collectors.toSet());
        Map<UUID, List<CaseFact>> factsByDebtor = facts.factsOfDebtors(debtors,
                        ctx.at().minus(Duration.ofDays(properties.factLookbackDays())))
                .stream().collect(Collectors.groupingBy(CaseFact::debtorId));
        Map<UUID, CaseScore> existing = scores.findAllByCaseIdIn(eligible.stream().map(CaseView::id).toList()).stream()
                .collect(Collectors.toMap(CaseScore::getCaseId, Function.identity()));
        ScoringFunction championFn = registry.function(ctx.champion());
        ScoringFunction challengerFn = ctx.challenger() == null ? null : registry.function(ctx.challenger());
        boolean snapshot = ctx.run() != null && ctx.run().isSnapshot();
        List<SnapshotRow> rows = new ArrayList<>();
        List<CaseScore> saved = new ArrayList<>();
        int published = 0;
        int bandChanges = 0;
        int failures = 0;
        for (CaseView c : eligible) {
            FeatureVector features;
            Evaluation evaluation;
            Evaluation shadow = null;
            try {
                features = FeatureCalculator.compute(inputs(c), factsByDebtor.getOrDefault(c.debtorId(), List.of()),
                        ctx.at(), rate(ctx.portfolioRates(), c.portfolioId()));
                evaluation = championFn.evaluate(features);
                if (snapshot && challengerFn != null) {
                    shadow = challengerFn.evaluate(features);
                }
            } catch (RuntimeException e) {
                failures++;
                metrics.skipped("error");
                LOG.atWarn().addKeyValue("caseId", c.id()).addKeyValue("modelId", ctx.champion().getId()).setCause(e)
                        .log("Falha ao calcular o score do caso; mantido o score anterior");
                continue;
            }
            CaseScore score = existing.getOrDefault(c.id(), new CaseScore(c.id(), c.debtorId()));
            if (score.getCalculatedAt() != null && score.getCalculatedAt().isAfter(ctx.at())) {
                // um cálculo mais recente (sob demanda/evento) já vigora: o lote só grava o snapshot do dia
                if (snapshot) {
                    rows.add(snapshotRow(c, features, evaluation, shadow, ctx));
                }
                continue;
            }
            String previousBand = score.getPublishedBand();
            Integer previousScore = score.getPublishedScore();
            score.record(c.creditorId(), c.portfolioId(), ctx.champion(), evaluation, json.toJson(features.toNames()),
                    json.toJson(evaluation.contributions()), ctx.referenceDate(), ctx.at(), ctx.trigger());
            metrics.scored(ctx.trigger().name(), ctx.champion().getKind().name(), score.getBand());
            boolean publish = score.shouldPublish(ctx.settings().getPublishMinDelta());
            boolean bandChanged = previousBand != null && !previousBand.equals(score.getBand());
            if (publish) {
                publish(c, score, previousScore, previousBand, ctx);
                published++;
                metrics.published(previousBand == null ? "first" : bandChanged ? "band_change" : "delta");
            }
            if (bandChanged) {
                bandChanges++;
                auditTrail.record(AuditEntry.of("case_score.band_changed", "collection_case", c.id())
                        .creditor(c.creditorId())
                        .change("band", previousBand, score.getBand())
                        .change("score", previousScore, score.getScore())
                        .meta("modelId", ctx.champion().getId()).meta("modelVersion", ctx.champion().getModelVersion())
                        .meta("trigger", ctx.trigger().name()));
            }
            if (publish || ctx.trigger() != CaseScore.Trigger.BATCH) {
                businessEvents.log(BusinessEvent.of(BusinessEventTypes.SCORE_CALCULATED)
                        .creditor(c.creditorId()).portfolio(c.portfolioId()).debtor(c.debtorId()).collectionCase(c.id())
                        .reference("case_score", score.getId())
                        .amount(c.openBalance())
                        .attr("score", score.getScore()).attr("band", score.getBand())
                        .attr("previousBand", previousBand).attr("bandChanged", bandChanged)
                        .attr("published", publish).attr("trigger", ctx.trigger().name())
                        .attr("modelKind", ctx.champion().getKind().name())
                        .attr("modelVersion", ctx.champion().getModelVersion())
                        .attr("outcomeDays", ctx.champion().getOutcomeDays())
                        .attr("caseStatus", c.status()).attr("daysOverdue", c.daysOverdue()));
            }
            saved.add(score);
            if (snapshot) {
                rows.add(snapshotRow(c, features, evaluation, shadow, ctx));
            }
        }
        List<CaseScore> persisted = new ArrayList<>();
        saved.forEach(s -> persisted.add(scores.save(s)));
        snapshots.insert(rows);
        return new Result(persisted.size(), skipped, published, bandChanges, failures, persisted);
    }

    private static SnapshotRow snapshotRow(CaseView c, FeatureVector features, Evaluation evaluation, Evaluation shadow,
                                           Context ctx) {
        int score = evaluation.score();
        return new SnapshotRow(ctx.run().getId(), ctx.referenceDate(), ctx.at(), c.id(), c.debtorId(), c.creditorId(),
                c.portfolioId(), c.assigneeUserId(), c.status(),
                c.openBalance() == null ? java.math.BigDecimal.ZERO : c.openBalance(), features, ctx.champion().getId(),
                evaluation.probability(), score, ctx.champion().bands().band(score),
                shadow == null ? null : ctx.challenger().getId(), shadow == null ? null : shadow.probability(),
                ctx.at().plus(Duration.ofDays(ctx.run().getOutcomeDays())));
    }

    private void publish(CaseView c, CaseScore score, Integer previousScore, String previousBand, Context ctx) {
        events.publishEvent(new CaseScoreChanged(Ids.newId(), currentActor.tenantId(), ctx.at(), c.id(), c.debtorId(),
                c.creditorId(), c.portfolioId(), score.getScore(), score.getBand(), previousScore, previousBand,
                ctx.champion().getId(), ctx.champion().getModelVersion(), ctx.champion().getKind().name(),
                ctx.champion().getOutcomeDays(), ctx.at()));
        score.markPublished(ctx.at());
    }

    /** Motivo para não escorar o caso, ou nulo. */
    static String skipReason(CaseView c) {
        if (NOT_SCORED.contains(c.status())) {
            return "LEGAL".equals(c.status()) ? "legal" : "closed";
        }
        if (c.eligibilityReasons() != null && c.eligibilityReasons().contains(ANONYMIZED)) {
            return "anonymized";
        }
        return null;
    }

    static CaseInputs inputs(CaseView c) {
        return new CaseInputs(c.id(), c.debtorId(), c.creditorId(), c.portfolioId(), c.assigneeUserId(), c.status(),
                c.resumeStatus(), c.daysOverdue(), c.openBalance(), c.openTitles(), c.openedAt(),
                c.lastEffectiveContactAt());
    }

    private static Double rate(Map<UUID, Double> rates, UUID portfolioId) {
        Double own = rates.get(portfolioId);
        return own != null ? own : rates.get(null);
    }

    /** Taxas serializáveis (chave da assessoria = {@code "_tenant"}) para os detalhes da execução. */
    static Map<String, Double> encodeRates(Map<UUID, Double> rates) {
        Map<String, Double> out = new LinkedHashMap<>();
        rates.forEach((k, v) -> out.put(k == null ? "_tenant" : k.toString(), v));
        return out;
    }

    static Map<UUID, Double> decodeRates(Map<String, ?> encoded) {
        Map<UUID, Double> out = new java.util.HashMap<>();
        if (encoded == null) {
            return out;
        }
        encoded.forEach((k, v) -> {
            if (v instanceof Number n) {
                out.put("_tenant".equals(k) ? null : UUID.fromString(k), n.doubleValue());
            }
        });
        return out;
    }
}
