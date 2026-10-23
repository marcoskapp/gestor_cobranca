package br.com.gestorcobranca.incentive.domain.model;

import br.com.gestorcobranca.incentive.domain.model.attribution.AgreementOrigin;
import br.com.gestorcobranca.incentive.domain.model.attribution.BeneficiaryType;
import br.com.gestorcobranca.incentive.domain.model.attribution.ParticipantRole;
import br.com.gestorcobranca.incentive.domain.model.ledger.CommissionLedgerEntry;
import br.com.gestorcobranca.incentive.domain.model.ledger.EntryKind;
import br.com.gestorcobranca.incentive.domain.model.ledger.EntryStatus;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.Percentage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommissionLedgerEntryTest {

    private static final Instant NOW = Instant.parse("2026-10-22T12:00:00Z");

    private static CommissionLedgerEntry accrual(String base, String weight, String factor) {
        return CommissionLedgerEntry.accrual(new CommissionLedgerEntry.Source(UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID(), new Segment(UUID.randomUUID(), null, DelayBucket.D31_90),
                        AgreementOrigin.ASSISTANT_HANDOFF_CONFIRMED, "PHONE"), BeneficiaryType.USER, UUID.randomUUID(),
                ParticipantRole.CLOSER, Money.of(base), Percentage.of(weight), Percentage.of(factor), "ORIGIN_WEIGHT",
                null, LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 12));
    }

    @Test
    void creditedAmountIsBaseTimesWeightTimesRenegotiationFactor() {
        // 1.000 × 30% × 50% = 150
        CommissionLedgerEntry entry = accrual("1000.00", "30", "50");
        assertThat(entry.getCreditedAmount()).isEqualTo(Money.of("150.00"));
        assertThat(entry.getStatus()).isEqualTo(EntryStatus.PENDING);
        assertThat(entry.getKind()).isEqualTo(EntryKind.ACCRUAL);
    }

    @Test
    void reversalOfAPendingEntryClearsTogetherWithIt() {
        CommissionLedgerEntry entry = accrual("1000.00", "100", "100");
        CommissionLedgerEntry reversal = CommissionLedgerEntry.reversalOf(entry, new BigDecimal("0.25"), UUID.randomUUID(),
                LocalDate.of(2026, 10, 7));
        assertThat(reversal.getBaseAmount()).isEqualTo(Money.of("-250.00"));
        assertThat(reversal.getCreditedAmount()).isEqualTo(Money.of("-250.00"));
        assertThat(reversal.getClearsOn()).isEqualTo(entry.getClearsOn());
        assertThat(reversal.getPaidOn()).isEqualTo(entry.getPaidOn());
        assertThatThrownBy(() -> CommissionLedgerEntry.reversalOf(reversal, BigDecimal.ONE, UUID.randomUUID(),
                LocalDate.of(2026, 10, 7))).isInstanceOf(ConflictException.class);
    }

    @Test
    void reversalOfAClearedEntryIsReleasedOnTheReversalDate() {
        CommissionLedgerEntry entry = accrual("1000.00", "100", "100");
        entry.clear(LocalDate.of(2026, 10, 1), NOW);
        CommissionLedgerEntry reversal = CommissionLedgerEntry.reversalOf(entry, BigDecimal.ONE, UUID.randomUUID(),
                LocalDate.of(2026, 10, 20));
        assertThat(reversal.getClearsOn()).isEqualTo(LocalDate.of(2026, 10, 20));
        reversal.waive();
        assertThat(reversal.getStatus()).isEqualTo(EntryStatus.WAIVED);
    }

    @Test
    void lifecycleGuards() {
        CommissionLedgerEntry entry = accrual("100.00", "100", "100");
        assertThatThrownBy(entry::waive).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> entry.includeIn(UUID.randomUUID())).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> entry.clear(LocalDate.of(2026, 10, 2), NOW)).isInstanceOf(BusinessRuleException.class);
        entry.clear(LocalDate.of(2026, 10, 1), NOW);
        assertThatThrownBy(() -> entry.clear(LocalDate.of(2026, 10, 1), NOW)).isInstanceOf(ConflictException.class);
        UUID statement = UUID.randomUUID();
        entry.includeIn(statement);
        assertThatThrownBy(() -> entry.includeIn(UUID.randomUUID())).isInstanceOf(ConflictException.class);
    }

    @Test
    void delayBuckets() {
        assertThat(DelayBucket.of(null)).isEqualTo(DelayBucket.UNKNOWN);
        assertThat(DelayBucket.of(0)).isEqualTo(DelayBucket.D0_30);
        assertThat(DelayBucket.of(30)).isEqualTo(DelayBucket.D0_30);
        assertThat(DelayBucket.of(31)).isEqualTo(DelayBucket.D31_90);
        assertThat(DelayBucket.of(180)).isEqualTo(DelayBucket.D91_180);
        assertThat(DelayBucket.of(360)).isEqualTo(DelayBucket.D181_360);
        assertThat(DelayBucket.of(361)).isEqualTo(DelayBucket.D361_PLUS);
    }
}
