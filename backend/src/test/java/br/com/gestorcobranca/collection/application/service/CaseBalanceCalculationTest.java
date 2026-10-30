package br.com.gestorcobranca.collection.application.service;

import br.com.gestorcobranca.collection.domain.model.CaseBalance;
import br.com.gestorcobranca.collection.domain.model.CloseReason;
import br.com.gestorcobranca.debt.application.port.in.DebtTitleUseCases.TitleView;
import br.com.gestorcobranca.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CaseBalanceCalculationTest {

    static TitleView title(String status, String balance, LocalDate due, boolean disputed) {
        return new TitleView(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, "X", 1, due,
                new BigDecimal(balance), new BigDecimal(balance), BigDecimal.ZERO, status, null, disputed, null, null, 0, null, 0L, null);
    }

    @Test
    void aggregatesTitlesInCollectionSeparatingEligibleBalance() {
        CaseBalance b = CaseSynchronizer.balance(List.of(
                title("OPEN", "100.00", LocalDate.of(2026, 5, 10), false),
                title("OPEN", "50.00", LocalDate.of(2026, 3, 1), true),
                title("SUSPENDED", "30.00", LocalDate.of(2026, 1, 1), false),
                title("SETTLED", "0.00", LocalDate.of(2025, 1, 1), false),
                title("CANCELLED", "999.00", LocalDate.of(2025, 1, 1), false)));
        assertThat(b.openBalance()).isEqualTo(Money.of("180.00"));
        assertThat(b.eligibleBalance()).isEqualTo(Money.of("100.00"));
        assertThat(b.openTitles()).isEqualTo(2);
        assertThat(b.suspendedTitles()).isEqualTo(1);
        assertThat(b.disputedTitles()).isEqualTo(1);
        assertThat(b.oldestDueDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(b.inCollection()).isTrue();
    }

    @Test
    void closeReasonFollowsTheTitlesDestination() {
        assertThat(CaseSynchronizer.closeReason(List.of(title("SETTLED", "0.00", LocalDate.now(), false))))
                .isEqualTo(CloseReason.SETTLED);
        assertThat(CaseSynchronizer.closeReason(List.of(title("RETURNED", "10.00", LocalDate.now(), false))))
                .isEqualTo(CloseReason.RETURNED);
        assertThat(CaseSynchronizer.closeReason(List.of(title("CANCELLED", "10.00", LocalDate.now(), false))))
                .isEqualTo(CloseReason.CANCELLED);
        assertThat(CaseSynchronizer.closeReason(List.of(title("SETTLED", "0.00", LocalDate.now(), false),
                title("RETURNED", "10.00", LocalDate.now(), false)))).isEqualTo(CloseReason.NO_OPEN_TITLES);
        assertThat(CaseSynchronizer.balance(List.of(title("OPEN", "0.00", LocalDate.now(), false))).inCollection())
                .isFalse();
    }
}
