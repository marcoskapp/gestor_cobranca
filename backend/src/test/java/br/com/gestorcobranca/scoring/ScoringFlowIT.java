package br.com.gestorcobranca.scoring;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.incentive.application.port.in.PerformanceQueries;
import br.com.gestorcobranca.incentive.application.port.in.PerformanceQueries.IndicatorQuery;
import br.com.gestorcobranca.incentive.application.port.in.PerformanceQueries.IndicatorReport;
import br.com.gestorcobranca.scoring.application.port.in.CaseScoreUseCases;
import br.com.gestorcobranca.scoring.application.port.in.CaseScoreUseCases.CaseScoreView;
import br.com.gestorcobranca.scoring.application.port.in.CaseScoreUseCases.ReviewCommand;
import br.com.gestorcobranca.scoring.application.port.in.CaseScoreUseCases.ReviewView;
import br.com.gestorcobranca.scoring.application.port.in.ModelUseCases;
import br.com.gestorcobranca.scoring.application.port.in.ModelUseCases.BinInput;
import br.com.gestorcobranca.scoring.application.port.in.ModelUseCases.DecisionCommand;
import br.com.gestorcobranca.scoring.application.port.in.ModelUseCases.ModelView;
import br.com.gestorcobranca.scoring.application.port.in.ModelUseCases.RuleInput;
import br.com.gestorcobranca.scoring.application.port.in.ModelUseCases.ScorecardCommand;
import br.com.gestorcobranca.scoring.application.port.in.ScoringOperations;
import br.com.gestorcobranca.scoring.application.port.in.ScoringOperations.ExpectedRecoveryReport;
import br.com.gestorcobranca.scoring.application.port.in.ScoringOperations.RunView;
import br.com.gestorcobranca.scoring.application.service.ScoringTestHooks;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.domain.ResourceNotFoundException;
import br.com.gestorcobranca.shared.events.collection.InteractionRecorded;
import br.com.gestorcobranca.shared.events.payment.PaymentConfirmed;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Fluxos do módulo scoring com PostgreSQL real: escoragem em lote (jobs com SKIP LOCKED),
 * {@code CaseScoreChanged} consumido pelo collection (ordem do "próximo caso") e pelo campaign
 * (projeção de audiência), explicação, revisão, desfecho e recuperado × esperado consumido pelo
 * incentive, promoção manual campeão × desafiante, critério mínimo, escopo e RLS.
 */
class ScoringFlowIT extends ScoringTestSupport {

    @Autowired
    CaseScoreUseCases caseScores;
    @Autowired
    ModelUseCases models;
    @Autowired
    ScoringOperations operations;
    @Autowired
    PerformanceQueries performance;

    @BeforeEach
    void setUp() {
        provision("scor");
    }

    @Test
    void withoutLegalBasisNothingIsScoredNorCollected() {
        CaseView c = loadCase(creditorA, portfolioA, CPFS[0], "500.00", 20);
        assertThatThrownBy(() -> asManager(manager, () -> operations.startBatch()))
                .isInstanceOf(ConflictException.class).extracting("code").isEqualTo("scoring.legal_basis_missing");
        assertThatThrownBy(() -> asOperator(operatorX, () -> caseScores.recalculate(c.id())))
                .isInstanceOf(ConflictException.class).extracting("code").isEqualTo("scoring.legal_basis_missing");
        publishAndAwait(new InteractionRecorded(Ids.newId(), tenant, Instant.now(), Ids.newId(), c.id(), c.debtorId(),
                c.creditorId(), "PHONE", "CPC", true, operatorX, "OUTBOUND", c.portfolioId()), "scoring.interaction");
        assertThat(count("select count(*) from scoring.case_fact")).isZero();
        assertThat(count("select count(*) from scoring.case_score")).isZero();
    }

    @Test
    void batchScoresCasesAndConsumersPrioritizeByScore() {
        enableScoring();
        // caso recente e com contato efetivo × caso antigo e sem contato
        CaseView fresh = loadCase(creditorA, portfolioA, CPFS[0], "300.00", 15);
        CaseView stale = loadCase(creditorA, portfolioA, CPFS[1], "25000.00", 400);
        CaseView other = loadCase(creditorB, portfolioB, CPFS[2], "800.00", 60);
        publishAndAwait(new InteractionRecorded(Ids.newId(), tenant, Instant.now().minusSeconds(3600), Ids.newId(),
                fresh.id(), fresh.debtorId(), fresh.creditorId(), "PHONE", "CPC", true, operatorX, "OUTBOUND",
                fresh.portfolioId()), "scoring.interaction");
        assertThat(count("select count(*) from scoring.case_fact where case_id = ?", fresh.id())).isEqualTo(2);

        RunView run = asManager(manager, () -> operations.startBatch());
        assertThat(run.snapshot()).isTrue();
        // reentrada no mesmo dia devolve a mesma execução
        assertThat(asManager(manager, () -> operations.startBatch()).id()).isEqualTo(run.id());
        drain("scoring.");
        RunView done = asManager(manager, () -> operations.run(run.id()));
        assertThat(done.status()).isEqualTo("COMPLETED");
        assertThat(done.scored()).isEqualTo(3);
        assertThat(done.published()).isEqualTo(3);
        assertThat(count("select count(*) from scoring.feature_snapshot where run_id = ?", run.id())).isEqualTo(3);
        assertThat(count("select count(*) from scoring.monitoring_report where run_id = ?", run.id())).isEqualTo(1);
        assertThat(count("select count(*) from scoring.model where status = 'CHAMPION' and kind = 'SCORECARD'"))
                .isEqualTo(1);
        assertThat(count("select count(*) from shared.business_event where event_type = 'score.calculated'"))
                .isEqualTo(3);
        assertThat(count("select count(*) from shared.business_event where event_type = 'score.batch_completed'"))
                .isEqualTo(1);

        CaseScoreView freshScore = asOperator(operatorX, () -> caseScores.get(fresh.id()));
        CaseScoreView staleScore = asOperator(operatorX, () -> caseScores.get(stale.id()));
        assertThat(freshScore.score()).isGreaterThan(staleScore.score());
        assertThat(freshScore.topFactors()).isNotEmpty().allSatisfy(f -> assertThat(f.explanation()).isNotBlank());
        assertThat(freshScore.factors()).extracting(CaseScoreUseCases.Factor::feature).contains("DAYS_OVERDUE");
        assertThat(freshScore.notice().legalBasis()).isEqualTo("LEGITIMATE_INTEREST");
        assertThat(freshScore.notice().purpose()).contains("Não é usado para negar atendimento");

        // collection guarda o score e entrega o maior primeiro na fila HIGHEST_SCORE
        awaitTrue("score no collection", () -> count("select count(*) from collection.collection_case "
                + "where score is not null and score_calculated_at is not null") == 3);
        assertThat(asOperator(operatorX, () -> work.nextCase(queueA))).get().extracting(CaseView::id)
                .isEqualTo(fresh.id());
        // campaign grava o score na projeção de audiência
        awaitTrue("score no campaign", () -> count("select count(*) from campaign.audience_case "
                + "where score is not null and score_calculated_at is not null") == 3);

        // escopo: operador restrito ao credor B não vê o score de um caso do credor A
        assertThatThrownBy(() -> asOperator(operatorZ, () -> caseScores.get(fresh.id())))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(asOperator(operatorZ, () -> caseScores.get(other.id())).creditorId()).isEqualTo(creditorB);
        // modelos alcançam todos os credores: escopo restrito não consulta
        assertThatThrownBy(() -> asOperator(operatorZ, () -> models.list()))
                .isInstanceOf(ScopeAccessDeniedException.class);
    }

    @Test
    void reviewRecordsPreviousAndNewScore() {
        enableScoring();
        CaseView c = loadCase(creditorA, portfolioA, CPFS[0], "700.00", 45);
        CaseScoreView first = asOperator(operatorX, () -> caseScores.recalculate(c.id()));
        ReviewView review = asManager(manager, () -> caseScores.review(c.id(),
                new ReviewCommand("DATA_SUBJECT_REQUEST", Ids.newId())));
        assertThat(review.previousScore()).isEqualTo(first.score());
        assertThat(review.newScore()).isEqualTo(review.score().score());
        assertThat(asOperator(operatorX, () -> caseScores.reviews(c.id()))).hasSize(1);
        assertThat(count("select count(*) from shared.audit_event where action = 'case_score.reviewed'")).isEqualTo(1);
        assertThat(count("select count(*) from shared.business_event where event_type = 'score.reviewed'")).isEqualTo(1);
        // revisão é append-only no banco
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update("update scoring.score_review set new_score = 1")))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void outcomesFeedRecoveredVersusExpectedInTheIncentive() {
        enableScoring();
        CaseView paying = loadCase(creditorA, portfolioA, CPFS[0], "1000.00", 20);
        loadCase(creditorA, portfolioA, CPFS[1], "1000.00", 20);
        CaseView taken = asOperator(operatorX, () -> work.nextCase(queueA)).orElseThrow();
        assertThat(taken.assigneeUserId()).isEqualTo(operatorX);
        RunView run = asManager(manager, () -> operations.startBatch());
        drain("scoring.");
        LocalDate referenceDate = run.referenceDate();

        publishAndAwait(new PaymentConfirmed(Ids.newId(), tenant, Instant.now(), Ids.newId(), paying.debtorId(),
                paying.creditorId(), paying.portfolioId(), null, new BigDecimal("400.00"),
                Instant.now().plus(Duration.ofDays(2)), "EXTERNAL", "PIX", List.of()), "scoring.payment-confirmed");

        // janela ainda aberta: nada apurado
        assertThat(system(() -> ScoringTestHooks.resolveOutcomes(applicationContext))).isZero();
        clock.advance(Duration.ofDays(40));
        assertThat(system(() -> ScoringTestHooks.resolveOutcomes(applicationContext))).isEqualTo(2);
        assertThat(count("select count(*) from scoring.feature_snapshot where outcome_paid and case_id = ?",
                paying.id())).isEqualTo(1);
        assertThat(system(() -> ScoringTestHooks.measureCohorts(applicationContext))).isEqualTo(2);
        assertThat(system(() -> ScoringTestHooks.measureCohorts(applicationContext))).isZero();

        ExpectedRecoveryReport report = asManager(manager, () -> operations.expectedRecovery(referenceDate, referenceDate,
                null, null, "NONE"));
        assertThat(report.lines()).singleElement().satisfies(l -> {
            assertThat(l.cases()).isEqualTo(2);
            assertThat(l.realizedPayers()).isEqualTo(1);
            assertThat(l.realizedAmount()).isEqualByComparingTo("400.00");
            assertThat(l.expectedAmount()).isPositive();
            assertThat(l.ratio()).isNotNull();
        });

        awaitTrue("recuperado × esperado no incentive",
                () -> count("select count(*) from incentive.expected_recovery_fact") == 2);
        BigDecimal expectedForX = system(() -> jdbcTemplate.queryForObject("""
                select expected_amount from incentive.expected_recovery_fact where user_id = ?
                """, BigDecimal.class, operatorX));
        IndicatorReport indicators = asManager(manager, () -> performance.indicators(new IndicatorQuery(referenceDate,
                referenceDate, operatorX, null, null, null, null, null, "NONE")));
        assertThat(indicators.unavailableMetrics()).doesNotContain("financial.expectedRecoveryRatio");
        BigDecimal realizedForX = taken.id().equals(paying.id()) ? new BigDecimal("400.00") : BigDecimal.ZERO;
        assertThat(indicators.rows()).singleElement().satisfies(r -> assertThat(r.financial().expectedRecoveryRatio())
                .isEqualByComparingTo(realizedForX.divide(expectedForX, 4, java.math.RoundingMode.HALF_EVEN)));
    }

    @Test
    void challengerIsPromotedOnlyManuallyByAnotherPerson() {
        enableScoring();
        ModelView champion = asManager(manager, () -> models.list()).getFirst();
        assertThat(champion.status()).isEqualTo("CHAMPION");

        // treino sem histórico: critério mínimo não atingido, assessoria segue no scorecard
        UUID trainingRun = asManager(manager, () -> models.train()).runId();
        drain("scoring.");
        RunView training = asManager(manager, () -> operations.run(trainingRun));
        assertThat(training.status()).isEqualTo("INSUFFICIENT_DATA");
        assertThat(training.details().get("reasons").toString()).contains("min_cases", "min_positives");

        ModelView challenger = asManager(manager, () -> models.createScorecard(new ScorecardCommand("Atraso pesa mais",
                500, 20, 500, new BigDecimal("0.111111"), List.of(new RuleInput("DAYS_OVERDUE", List.of(
                new BinInput(null, new BigDecimal("31"), 40), new BinInput(new BigDecimal("31"), null, -40)), 0)))));
        assertThat(challenger.status()).isEqualTo("CHALLENGER");
        assertThatThrownBy(() -> asManager(manager, () -> models.createScorecard(new ScorecardCommand(null, 500, 20, 500,
                new BigDecimal("0.11"), List.of(new RuleInput("GENDER", List.of(), 0))))))
                .extracting("code").isEqualTo("scorecard.feature_invalid");

        assertThatThrownBy(() -> asManager(manager, () -> models.promote(challenger.id(),
                new DecisionCommand("Validado no retroteste do trimestre"))))
                .isInstanceOf(ConflictException.class).extracting("code").isEqualTo("scoring.model.self_promotion");
        assertThatThrownBy(() -> asManager(manager2, () -> models.promote(champion.id(),
                new DecisionCommand("Tentativa sobre o campeão atual"))))
                .isInstanceOf(ConflictException.class).extracting("code").isEqualTo("scoring.model.not_challenger");

        ModelView promoted = asManager(manager2, () -> models.promote(challenger.id(),
                new DecisionCommand("Desafiante aprovado no comitê de 24/10")));
        assertThat(promoted.status()).isEqualTo("CHAMPION");
        assertThat(asManager(manager, () -> models.get(champion.id())).status()).isEqualTo("RETIRED");
        assertThat(asManager(manager, () -> models.decisions())).singleElement().satisfies(d -> {
            assertThat(d.decision()).isEqualTo("PROMOTE");
            assertThat(d.previousChampionId()).isEqualTo(champion.id());
            assertThat(d.decidedBy()).isEqualTo(manager2);
            assertThat(d.comparison()).containsKey("challenger");
        });
        assertThat(count("select count(*) from shared.audit_event where action = 'scoring_model.promoted'"))
                .isEqualTo(1);
        assertThat(count("select count(*) from shared.business_event where event_type = 'score.model_promoted'"))
                .isEqualTo(1);
        // definição do modelo é imutável no banco
        assertThatThrownBy(() -> system(() -> jdbcTemplate.update("update scoring.model set definition = '{}'")))
                .isInstanceOf(RuntimeException.class);

        // novo campeão passa a escorar
        CaseView c = loadCase(creditorA, portfolioA, CPFS[0], "500.00", 10);
        assertThat(asOperator(operatorX, () -> caseScores.recalculate(c.id())).modelId()).isEqualTo(promoted.id());
    }

    @Test
    void scoresAreIsolatedByTenant() {
        enableScoring();
        CaseView c = loadCase(creditorA, portfolioA, CPFS[0], "500.00", 10);
        asOperator(operatorX, () -> caseScores.recalculate(c.id()));
        assertThat(count("select count(*) from scoring.case_score")).isEqualTo(1);
        UUID mine = tenant;
        provision("scor2");
        assertThat(tenant).isNotEqualTo(mine);
        assertThat(count("select count(*) from scoring.case_score")).isZero();
        assertThat(count("select count(*) from scoring.model")).isZero();
        assertThat(count("select count(*) from scoring.settings")).isZero();
        // sem tenant na transação nenhuma linha é visível
        Long direct = jdbcTemplate.queryForObject("select count(*) from scoring.case_score", Long.class);
        assertThat(direct).isZero();
    }
}
