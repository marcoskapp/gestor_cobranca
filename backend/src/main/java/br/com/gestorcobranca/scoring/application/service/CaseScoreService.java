package br.com.gestorcobranca.scoring.application.service;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases;
import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.scoring.application.port.in.CaseScoreUseCases;
import br.com.gestorcobranca.scoring.application.port.out.SnapshotStore;
import br.com.gestorcobranca.scoring.domain.model.CaseScore;
import br.com.gestorcobranca.scoring.domain.model.ScoreReview;
import br.com.gestorcobranca.scoring.domain.model.ScoringModel;
import br.com.gestorcobranca.scoring.domain.model.ScoringSettings;
import br.com.gestorcobranca.scoring.domain.repository.CaseScoreRepository;
import br.com.gestorcobranca.scoring.domain.repository.ScoreReviewRepository;
import br.com.gestorcobranca.shared.audit.AuditEntry;
import br.com.gestorcobranca.shared.audit.AuditTrail;
import br.com.gestorcobranca.shared.config.TimeConfig;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.logging.business.BusinessEvent;
import br.com.gestorcobranca.shared.logging.business.BusinessEventLogger;
import br.com.gestorcobranca.shared.logging.business.BusinessEventTypes;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Score do caso: consulta com explicação, histórico, recálculo sob demanda, revisão (LGPD art. 20)
 * e recálculo por evento. O escopo de credores vem do {@code collection}: o caso só é lido se
 * estiver visível ao ator (404 fora do escopo); comandos exigem o credor no escopo (403).
 */
@Service
@Transactional
class CaseScoreService implements CaseScoreUseCases {

    private static final int HISTORY_LIMIT = 60;

    private final CaseUseCases cases;
    private final CaseScoreRepository scores;
    private final ScoreReviewRepository reviews;
    private final SnapshotStore snapshots;
    private final ScoringSettingsProvider settingsProvider;
    private final ModelRegistry registry;
    private final CaseScorer scorer;
    private final ScoreBatchService batch;
    private final ScoringViews views;
    private final AccessScope accessScope;
    private final CurrentActor currentActor;
    private final AuditTrail auditTrail;
    private final BusinessEventLogger businessEvents;
    private final ScoringMetrics metrics;
    private final Clock clock;

    CaseScoreService(CaseUseCases cases, CaseScoreRepository scores, ScoreReviewRepository reviews,
                     SnapshotStore snapshots, ScoringSettingsProvider settingsProvider, ModelRegistry registry,
                     CaseScorer scorer, ScoreBatchService batch, ScoringViews views, AccessScope accessScope,
                     CurrentActor currentActor, AuditTrail auditTrail, BusinessEventLogger businessEvents,
                     ScoringMetrics metrics, Clock clock) {
        this.cases = cases;
        this.scores = scores;
        this.reviews = reviews;
        this.snapshots = snapshots;
        this.settingsProvider = settingsProvider;
        this.registry = registry;
        this.scorer = scorer;
        this.batch = batch;
        this.views = views;
        this.accessScope = accessScope;
        this.currentActor = currentActor;
        this.auditTrail = auditTrail;
        this.businessEvents = businessEvents;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public CaseScoreView get(UUID caseId) {
        CaseView c = cases.get(caseId);
        CaseScore score = scores.findByCaseId(c.id()).orElseThrow(() -> new ResourceNotFoundException("case_score", caseId));
        return view(score);
    }

    @Override
    @Transactional(readOnly = true)
    public List<HistoryEntry> history(UUID caseId) {
        CaseView c = cases.get(caseId);
        return snapshots.history(c.id(), HISTORY_LIMIT).stream()
                .map(h -> new HistoryEntry(h.referenceDate(), h.score(), h.band(), h.modelId(), h.paid())).toList();
    }

    @Override
    public CaseScoreView recalculate(UUID caseId) {
        long started = System.nanoTime();
        CaseView c = cases.get(caseId);
        accessScope.requireCreditor(c.creditorId());
        CaseScore score = scoreNow(c, CaseScore.Trigger.ON_DEMAND, requireEnabled());
        metrics.onDemand("scored", Duration.ofNanos(System.nanoTime() - started));
        return view(score);
    }

    @Override
    public ReviewView review(UUID caseId, ReviewCommand command) {
        CaseView c = cases.get(caseId);
        accessScope.requireCreditor(c.creditorId());
        ScoreReview.Origin origin = ScoringViews.parse(ScoreReview.Origin.class, command.origin(),
                "scoring.review.origin_invalid", "Origem da revisão");
        ScoringSettings settings = requireEnabled();
        Optional<CaseScore> previous = scores.findByCaseId(c.id());
        Integer previousScore = previous.map(CaseScore::getScore).orElse(null);
        String previousBand = previous.map(CaseScore::getBand).orElse(null);
        UUID previousModel = previous.map(CaseScore::getModelId).orElse(null);
        CaseScore score = scoreNow(c, CaseScore.Trigger.REVIEW, settings);
        Instant now = Instant.now(clock);
        ScoreReview review = reviews.save(new ScoreReview(c.id(), c.creditorId(), origin, command.requestReference(),
                previousScore, previousBand, previousModel, score.getScore(), score.getBand(), score.getModelId(),
                currentActor.actorId(), now));
        auditTrail.record(AuditEntry.of("case_score.reviewed", "collection_case", c.id())
                .creditor(c.creditorId())
                .change("score", previousScore, score.getScore())
                .change("band", previousBand, score.getBand())
                .change("modelId", previousModel, score.getModelId())
                .meta("origin", origin.name()).meta("requestReference", command.requestReference())
                .meta("reviewId", review.getId()));
        businessEvents.log(BusinessEvent.of(BusinessEventTypes.SCORE_REVIEWED)
                .creditor(c.creditorId()).portfolio(c.portfolioId()).debtor(c.debtorId()).collectionCase(c.id())
                .reference("score_review", review.getId())
                .attr("origin", origin.name()).attr("previousBand", previousBand).attr("band", score.getBand())
                .attr("bandChanged", previousBand != null && !previousBand.equals(score.getBand()))
                .attr("modelChanged", previousModel != null && !previousModel.equals(score.getModelId())));
        return review(review, view(score));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReviewView> reviews(UUID caseId) {
        CaseView c = cases.get(caseId);
        return reviews.findByCaseIdOrderByReviewedAtDesc(c.id()).stream().map(r -> review(r, null)).toList();
    }

    /** Recálculo disparado por fato relevante (job, ator de sistema); sem base legal, nada é feito. */
    void rescoreByEvent(UUID caseId) {
        if (!settingsProvider.enabled()) {
            return;
        }
        CaseView c = cases.get(caseId);
        if (CaseScorer.skipReason(c) != null) {
            return;
        }
        scoreNow(c, CaseScore.Trigger.EVENT, settingsProvider.current());
    }

    private CaseScore scoreNow(CaseView c, CaseScore.Trigger trigger, ScoringSettings settings) {
        if (CaseScorer.skipReason(c) != null) {
            throw new ConflictException("scoring.case_not_scorable",
                    "Caso não é escorado (encerrado, no jurídico ou devedor anonimizado)");
        }
        ScoringModel champion = registry.ensureChampion(settings);
        Instant now = Instant.now(clock);
        CaseScorer.Result result = scorer.score(List.of(c), new CaseScorer.Context(settings, champion, null,
                batch.currentRates(), LocalDate.ofInstant(now, TimeConfig.BUSINESS_ZONE), now, trigger, null));
        if (result.scores().isEmpty()) {
            // cálculo falhou (registrado no log técnico) ou um cálculo mais recente já vigora
            return scores.findByCaseId(c.id()).orElseThrow(() -> new ConflictException("scoring.calculation_failed",
                    "Não foi possível calcular o score do caso"));
        }
        return result.scores().getFirst();
    }

    private ScoringSettings requireEnabled() {
        ScoringSettings settings = settingsProvider.current();
        if (!settings.legalBasisRegistered()) {
            throw new ConflictException("scoring.legal_basis_missing",
                    "Score desabilitado: registre a base legal do tratamento (configurações do score)");
        }
        return settings;
    }

    private CaseScoreView view(CaseScore score) {
        ScoringModel model = registry.find(score.getModelId()).orElseThrow();
        return views.caseScore(score, settingsProvider.existing().orElse(null), model.getOutcomeDays());
    }

    private static ReviewView review(ScoreReview r, CaseScoreView score) {
        return new ReviewView(r.getId(), r.getCaseId(), r.getOrigin().name(), r.getRequestReference(),
                r.getPreviousScore(), r.getPreviousBand(), r.getPreviousModelId(), r.getNewScore(), r.getNewBand(),
                r.getNewModelId(), r.getReviewedBy(), r.getReviewedAt(), score);
    }
}
