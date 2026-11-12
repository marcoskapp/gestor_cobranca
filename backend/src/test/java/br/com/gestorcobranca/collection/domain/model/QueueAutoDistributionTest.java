package br.com.gestorcobranca.collection.domain.model;

import br.com.gestorcobranca.collection.domain.model.CollectionQueue.AutoDistribution;
import br.com.gestorcobranca.collection.domain.model.CollectionQueue.QueueDefinition;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Distribuição automática da fila e a exigência de capacidade da estratégia por nível (distribuicao-por-desempenho.md §4.3). */
class QueueAutoDistributionTest {

    static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    static QueueDefinition definition(DistributionStrategy strategy, Integer capacity, AutoDistribution auto) {
        return new QueueDefinition("Fila", null, 1, null, null, null, null, null, null, null, null,
                CaseOrdering.OLDEST_OVERDUE, strategy, capacity, Set.of(), false, false, null, auto);
    }

    static AutoDistribution auto(String... times) {
        return new AutoDistribution(true, CollectionQueue.parseTimes(List.of(times)), 100);
    }

    static ZonedDateTime at(int hour, int minute) {
        return ZonedDateTime.of(2026, 11, 11, hour, minute, 0, 0, ZONE);
    }

    @Test
    void tieredQueueRequiresCapacity() {
        assertThatThrownBy(() -> new CollectionQueue("TT", definition(DistributionStrategy.TIERED, null, null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "queue.tiered_capacity_required");
        assertThat(new CollectionQueue("TT", definition(DistributionStrategy.TIERED, 30, null)).getStrategy())
                .isEqualTo(DistributionStrategy.TIERED);
    }

    @Test
    void pullQueueDoesNotAcceptAutoDistribution() {
        assertThatThrownBy(() -> new CollectionQueue("PP", definition(DistributionStrategy.PULL, null, auto("08:00"))))
                .isInstanceOf(BusinessRuleException.class)
                .hasFieldOrPropertyWithValue("code", "queue.auto_distribution_pull");
        assertThatThrownBy(() -> new CollectionQueue("RR", definition(DistributionStrategy.ROUND_ROBIN, null,
                new AutoDistribution(true, List.of(), 100)))).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> new CollectionQueue("RR", definition(DistributionStrategy.ROUND_ROBIN, null,
                new AutoDistribution(true, List.of(LocalTime.of(8, 0)), null)))).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> CollectionQueue.parseTimes(List.of("8h"))).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void timesAreSortedDistinctAndRoundTrip() {
        CollectionQueue q = new CollectionQueue("RR", definition(DistributionStrategy.ROUND_ROBIN, null,
                auto("13:00", "08:00", "13:00")));
        assertThat(q.autoDistribution().times()).containsExactly(LocalTime.of(8, 0), LocalTime.of(13, 0));
        assertThat(q.autoDistribution().limit()).isEqualTo(100);
        assertThat(q.autoDistribution().enabled()).isTrue();
    }

    @Test
    void oneRoundPerSlotPerDay() {
        CollectionQueue q = new CollectionQueue("RR", definition(DistributionStrategy.LEAST_LOADED, null,
                auto("08:00", "13:00")));
        q.autoDistributionHandled(at(7, 0).toInstant());
        assertThat(q.dueAutoDistribution(at(7, 59))).isEmpty();
        assertThat(q.dueAutoDistribution(at(8, 0))).contains(LocalTime.of(8, 0));
        q.autoDistributionHandled(at(8, 0).toInstant());
        assertThat(q.dueAutoDistribution(at(8, 1))).as("o mesmo horário não roda de novo").isEmpty();
        assertThat(q.dueAutoDistribution(at(13, 5))).contains(LocalTime.of(13, 0));
        // instância parada nos dois horários: uma única rodada pelo último horário vencido
        q.autoDistributionHandled(at(7, 0).toInstant());
        assertThat(q.dueAutoDistribution(at(15, 0))).contains(LocalTime.of(13, 0));
        // configuração alterada às 10h: o horário das 8h do dia não dispara
        q.autoDistributionHandled(at(10, 0).toInstant());
        assertThat(q.dueAutoDistribution(at(10, 1))).isEmpty();
        // desativada: nada
        q.deactivate();
        assertThat(q.dueAutoDistribution(at(13, 5))).isEmpty();
    }
}
