package br.com.gestorcobranca.collection;

import br.com.gestorcobranca.collection.application.port.in.CaseUseCases.CaseView;
import br.com.gestorcobranca.collection.application.port.in.QueueUseCases.QueueCommand;
import br.com.gestorcobranca.collection.application.port.in.QueueUseCases.QueueView;
import br.com.gestorcobranca.shared.domain.Ids;
import br.com.gestorcobranca.shared.events.scoring.CaseScoreChanged;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Consumo de {@code CaseScoreChanged} (módulo scoring) pelo collection: o score fica no caso, a fila
 * com ordem {@code HIGHEST_SCORE} entrega o maior score primeiro, o critério de faixa de score
 * reencaminha o caso e entregas fora de ordem (cálculo mais antigo) são ignoradas.
 */
class CollectionScoreOrderingIT extends CollectionTestSupport {

    @BeforeEach
    void setUp() {
        provision("score");
    }

    @Test
    void nextCaseFollowsScoreWhenQueueIsOrderedByScore() {
        QueueView queue = asManager(() -> queues.create(new QueueCommand("POR_SCORE", "Por score", null, 10, creditorA,
                null, null, null, null, null, null, null, "HIGHEST_SCORE", "PULL", null, Set.of(team))));
        CaseView low = loadCase("083.016.613-05", "500.00");
        CaseView high = loadCase("186.091.390-34", "500.00");
        CaseView middle = loadCase("526.018.159-06", "500.00");
        assertThat(List.of(low, high, middle)).allSatisfy(c -> assertThat(c.queueId()).isEqualTo(queue.id()));

        Instant at = Instant.now();
        score(low, 150, "C", at);
        score(high, 820, "A", at);
        score(middle, 410, "A", at);
        assertThat(activeCase(high.debtorId(), portfolioA).score()).isEqualTo(820);

        // entrega fora de ordem: um cálculo mais antigo não substitui o vigente
        score(high, 10, "E", at.minus(Duration.ofHours(1)));
        assertThat(activeCase(high.debtorId(), portfolioA).score()).isEqualTo(820);

        assertThat(asOperator(operatorX, () -> work.nextCase(queue.id()))).get().extracting(CaseView::id)
                .isEqualTo(high.id());
        assertThat(asOperator(operatorY, () -> work.nextCase(queue.id()))).get().extracting(CaseView::id)
                .isEqualTo(middle.id());
        assertThat(asOperator(operatorX, () -> work.nextCase(queue.id()))).get().extracting(CaseView::id)
                .isEqualTo(low.id());
    }

    @Test
    void scoreBandCriterionReroutesTheCase() {
        QueueView priority = asManager(() -> queues.create(new QueueCommand("ALTA_PROPENSAO", "Alta propensão", null, 1,
                creditorA, null, null, null, null, null, 400, null, "HIGHEST_SCORE", "PULL", null, Set.of(team))));
        QueueView general = asManager(() -> queues.create(queue("GERAL_A", 50, creditorA, "PULL", null, Set.of(team))));
        CaseView c = loadCase("083.016.613-05", "800.00");
        // sem score o critério de faixa não é atendido: fila geral
        assertThat(c.queueId()).isEqualTo(general.id());

        score(c, 640, "A", Instant.now());
        assertThat(activeCase(c.debtorId(), portfolioA).queueId()).isEqualTo(priority.id());
        assertThat(count("select count(*) from collection.case_assignment where case_id = ? and queue_id = ?",
                c.id(), priority.id())).isEqualTo(1);

        score(c, 120, "D", Instant.now().plusSeconds(1));
        assertThat(activeCase(c.debtorId(), portfolioA).queueId()).isEqualTo(general.id());
    }

    private CaseView loadCase(String cpf, String principal) {
        UUID debtor = debtor(cpf);
        load(creditorA, portfolioA, debtor, principal);
        return activeCase(debtor, portfolioA);
    }

    private void score(CaseView c, int score, String band, Instant calculatedAt) {
        publishAndAwait(new CaseScoreChanged(Ids.newId(), tenant, calculatedAt, c.id(), c.debtorId(), c.creditorId(),
                c.portfolioId(), score, band, null, null, Ids.newId(), 1, "SCORECARD", 30, calculatedAt),
                "collection.case-score");
    }
}
