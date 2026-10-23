package br.com.gestorcobranca.collection.domain.service;

import br.com.gestorcobranca.collection.domain.model.CaseBalance;
import br.com.gestorcobranca.collection.domain.model.CaseStatus;
import br.com.gestorcobranca.collection.domain.model.Eligibility;
import br.com.gestorcobranca.collection.domain.model.EligibilityReason;
import br.com.gestorcobranca.collection.domain.model.InteractionChannel;
import br.com.gestorcobranca.collection.domain.service.EligibilityPolicy.ChannelAvailability;
import br.com.gestorcobranca.collection.domain.service.EligibilityPolicy.Input;
import br.com.gestorcobranca.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EligibilityPolicyTest {

    static final CaseBalance OPEN = new CaseBalance(Money.of("500.00"), Money.of("500.00"), 2, 0, 0, LocalDate.of(2026, 5, 1));
    static final Map<InteractionChannel, ChannelAvailability> ALL = Map.of(
            InteractionChannel.PHONE, ChannelAvailability.AVAILABLE, InteractionChannel.WHATSAPP, ChannelAvailability.AVAILABLE,
            InteractionChannel.EMAIL, ChannelAvailability.RESTRICTED, InteractionChannel.SMS, ChannelAvailability.AVAILABLE);

    @Test
    void eligibleWithActivePortfolioOpenTitlesAndAChannel() {
        Eligibility e = EligibilityPolicy.evaluate(new Input(CaseStatus.OPEN, true, OPEN, false, false, ALL));
        assertThat(e.eligible()).isTrue();
        assertThat(e.reasons()).isEmpty();
        assertThat(e.contactableChannels()).containsExactlyInAnyOrder(InteractionChannel.PHONE, InteractionChannel.WHATSAPP,
                InteractionChannel.SMS);
    }

    @Test
    void collectsEveryReasonAtOnce() {
        CaseBalance disputed = new CaseBalance(Money.of("500.00"), Money.ZERO, 1, 0, 1, LocalDate.of(2026, 5, 1));
        Map<InteractionChannel, ChannelAvailability> none = Map.of(InteractionChannel.PHONE, ChannelAvailability.RESTRICTED,
                InteractionChannel.EMAIL, ChannelAvailability.RESTRICTED);
        Eligibility e = EligibilityPolicy.evaluate(new Input(CaseStatus.SUSPENDED, false, disputed, true, false, none));
        assertThat(e.eligible()).isFalse();
        assertThat(e.reasons()).containsExactlyInAnyOrder(EligibilityReason.CASE_SUSPENDED, EligibilityReason.DISPUTE_OPEN,
                EligibilityReason.PORTFOLIO_NOT_OPERABLE, EligibilityReason.TITLES_DISPUTED,
                EligibilityReason.CONTACT_RESTRICTED);
    }

    @Test
    void onlySuspendedTitlesMeansNoOpenTitles() {
        CaseBalance suspended = new CaseBalance(Money.of("100.00"), Money.ZERO, 0, 1, 0, LocalDate.of(2026, 5, 1));
        Eligibility e = EligibilityPolicy.evaluate(new Input(CaseStatus.OPEN, true, suspended, false, false, ALL));
        assertThat(e.reasons()).containsExactly(EligibilityReason.NO_OPEN_TITLES);
    }

    @Test
    void anonymizedDebtorAndClosedCase() {
        assertThat(EligibilityPolicy.evaluate(new Input(CaseStatus.OPEN, true, OPEN, false, true, Map.of())).reasons())
                .containsExactly(EligibilityReason.DEBTOR_ANONYMIZED);
        assertThat(EligibilityPolicy.evaluate(new Input(CaseStatus.CLOSED, true, OPEN, false, false, ALL)).reasons())
                .containsExactly(EligibilityReason.CASE_CLOSED);
        assertThat(EligibilityPolicy.evaluate(new Input(CaseStatus.LEGAL, true, OPEN, false, false, ALL)).reasons())
                .containsExactly(EligibilityReason.CASE_LEGAL);
    }
}
