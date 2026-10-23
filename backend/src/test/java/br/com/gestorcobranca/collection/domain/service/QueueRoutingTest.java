package br.com.gestorcobranca.collection.domain.service;

import br.com.gestorcobranca.collection.domain.model.CaseBalance;
import br.com.gestorcobranca.collection.domain.model.CaseOrdering;
import br.com.gestorcobranca.collection.domain.model.CloseReason;
import br.com.gestorcobranca.collection.domain.model.CollectionCase;
import br.com.gestorcobranca.collection.domain.model.CollectionQueue;
import br.com.gestorcobranca.collection.domain.model.CollectionQueue.QueueDefinition;
import br.com.gestorcobranca.collection.domain.model.DistributionStrategy;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class QueueRoutingTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 23);
    static final UUID CREDITOR = UUID.randomUUID();

    static CollectionQueue queue(String code, int priority, UUID creditor, Integer minOverdue, Integer maxOverdue,
                                 String minBalance) {
        return new CollectionQueue(code, new QueueDefinition("Fila " + code, null, priority, creditor, null, minOverdue,
                maxOverdue, minBalance == null ? null : Money.of(minBalance), null, null, null, CaseOrdering.OLDEST_OVERDUE,
                DistributionStrategy.PULL, null, Set.of()));
    }

    static CollectionCase caseOf(UUID creditor, int daysOverdue, String balance) {
        return new CollectionCase(UUID.randomUUID(), creditor, UUID.randomUUID(),
                new CaseBalance(Money.of(balance), Money.of(balance), 1, 0, 0, TODAY.minusDays(daysOverdue)),
                Instant.parse("2026-10-23T12:00:00Z"));
    }

    @Test
    void firstMatchingActiveQueueByPriorityWins() {
        CollectionQueue early = queue("EARLY", 10, CREDITOR, 0, 30, null);
        CollectionQueue late = queue("LATE", 20, CREDITOR, 31, null, null);
        CollectionQueue vip = queue("VIP", 5, null, null, null, "10000.00");
        CollectionQueue fallback = queue("ALL", 100, null, null, null, null);
        List<CollectionQueue> queues = List.of(fallback, late, early, vip);
        assertThat(QueueRouter.route(caseOf(CREDITOR, 10, "100.00"), queues, TODAY)).contains(early);
        assertThat(QueueRouter.route(caseOf(CREDITOR, 90, "100.00"), queues, TODAY)).contains(late);
        assertThat(QueueRouter.route(caseOf(CREDITOR, 90, "20000.00"), queues, TODAY)).contains(vip);
        assertThat(QueueRouter.route(caseOf(UUID.randomUUID(), 10, "100.00"), queues, TODAY)).contains(fallback);
        vip.deactivate();
        assertThat(QueueRouter.route(caseOf(CREDITOR, 90, "20000.00"), queues, TODAY)).contains(late);
    }

    @Test
    void closedCasesAndScoreCriteria() {
        CollectionCase closed = caseOf(CREDITOR, 10, "100.00");
        closed.close(CloseReason.MANUAL, "x", Instant.now());
        assertThat(QueueRouter.route(closed, List.of(queue("ALL", 1, null, null, null, null)), TODAY)).isEmpty();
        CollectionQueue scored = new CollectionQueue("SCORE", new QueueDefinition("Score", null, 1, null, null, null, null,
                null, null, 500, null, null, null, null, Set.of()));
        assertThat(scored.matches(caseOf(CREDITOR, 1, "1.00"), TODAY)).isFalse();
    }

    @Test
    void definitionIsValidated() {
        assertThatThrownBy(() -> queue("BAD", 1, null, 30, 10, null)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> new CollectionQueue("P", new QueueDefinition("x", null, 1, null, UUID.randomUUID(), null,
                null, null, null, null, null, null, null, null, Set.of()))).isInstanceOf(BusinessRuleException.class);
        CollectionQueue q = queue("served", 1, null, null, null, null);
        assertThat(q.getCode()).isEqualTo("SERVED");
        assertThat(q.isServedBy(Set.of())).isTrue();
    }

    @Test
    void bandsAreStableLabels() {
        assertThat(OperationBands.overdueBand(0)).isEqualTo("0-30");
        assertThat(OperationBands.overdueBand(31)).isEqualTo("31-90");
        assertThat(OperationBands.overdueBand(400)).isEqualTo("361+");
        assertThat(OperationBands.valueBand(Money.of("499.99"))).isEqualTo("0-500");
        assertThat(OperationBands.valueBand(Money.of("500.00"))).isEqualTo("500-1k");
        assertThat(OperationBands.valueBand(Money.of("25000.00"))).isEqualTo("20k+");
    }
}
