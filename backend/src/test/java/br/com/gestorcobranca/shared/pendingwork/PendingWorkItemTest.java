package br.com.gestorcobranca.shared.pendingwork;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PendingWorkItemTest {

    private static final Instant T = Instant.parse("2026-10-26T12:00:00Z");

    @Test
    void severityFollowsOverdueThenDueToday() {
        assertThat(new PendingWorkCount(3, 1, 1, T, LocalDate.of(2026, 10, 25), null).severity())
                .isEqualTo(PendingWorkSeverity.OVERDUE);
        assertThat(new PendingWorkCount(3, 0, 2, T, LocalDate.of(2026, 10, 26), null).severity())
                .isEqualTo(PendingWorkSeverity.DUE_TODAY);
        assertThat(PendingWorkCount.of(3, T).severity()).isEqualTo(PendingWorkSeverity.NORMAL);
    }

    @Test
    void countsMustBeConsistentAndSingleIdOnlyWithOneItem() {
        assertThatThrownBy(() -> new PendingWorkCount(1, 1, 1, T, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PendingWorkCount(-1, 0, 0, T, null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new PendingWorkCount(2, 0, 0, T, null, UUID.randomUUID()).singleId()).isNull();
    }

    @Test
    void singleItemOpensTheItemAndManyOpenTheFilteredList() {
        UUID id = UUID.randomUUID();
        PendingWorkItem one = PendingWorkItem.of("payment.external_review", "Pagamentos",
                        new PendingWorkCount(1, 0, 0, T, null, id))
                .route("/pagamentos/aprovacoes").param("situacao", "OPEN").singleRoute(x -> "/pagamentos/" + x).build();
        assertThat(one.route()).isEqualTo("/pagamentos/" + id);
        assertThat(one.queryParams()).isEmpty();

        PendingWorkItem many = PendingWorkItem.of("payment.external_review", "Pagamentos", PendingWorkCount.of(4, T))
                .route("/pagamentos/aprovacoes").param("situacao", "OPEN").param("ignorado", null)
                .singleRoute(x -> "/pagamentos/" + x).build();
        assertThat(many.route()).isEqualTo("/pagamentos/aprovacoes");
        assertThat(many.queryParams()).containsExactly(Map.entry("situacao", "OPEN"));
    }

    @Test
    void rejectsUnstableTypesRoutesAndFreeTextFilters() {
        PendingWorkCount c = PendingWorkCount.of(1, T);
        assertThatThrownBy(() -> PendingWorkItem.of("Pagamento", "x", c).route("/a").build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PendingWorkItem.of("payment.review", "x", c).route("https://x").build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PendingWorkItem.of("payment.review", "x", c).route("/a?b=1").build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PendingWorkItem.of("payment.review", "x", c).route("/a").param("nome", "Maria Silva")
                .build()).isInstanceOf(IllegalArgumentException.class);
    }
}
