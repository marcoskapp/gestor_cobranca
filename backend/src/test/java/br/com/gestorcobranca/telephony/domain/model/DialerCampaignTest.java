package br.com.gestorcobranca.telephony.domain.model;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Percentage;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DialerCampaignTest {

    static DialerCampaign.Settings settings(DialerMode mode, String ratio, String abandon) {
        return new DialerCampaign.Settings("Discador", null, false, mode, ratio == null ? null : new BigDecimal(ratio),
                abandon == null ? null : Percentage.of(abandon), null, null, null, LocalTime.of(9, 0), LocalTime.of(18, 0));
    }

    @Test
    void marketDefaultsAndProgressiveRatio() {
        DialerCampaign campaign = new DialerCampaign("cob-d30", null, UUID.randomUUID(),
                settings(DialerMode.PROGRESSIVE, "3.00", null));
        assertThat(campaign.getCode()).isEqualTo("COB-D30");
        assertThat(campaign.getMaxDialRatio()).isEqualByComparingTo("1");
        assertThat(campaign.getMaxAbandonRate()).isEqualTo(Percentage.of("3"));
        assertThat(campaign.getMaxAttempts()).isEqualTo(3);
        assertThat(campaign.getRetryIntervalMinutes()).isEqualTo(120);
        assertThat(campaign.getRingTimeoutSeconds()).isEqualTo(25);
        assertThat(campaign.withinWindow(LocalTime.of(8, 59))).isFalse();
        assertThat(campaign.withinWindow(LocalTime.of(9, 0))).isTrue();
        assertThat(campaign.withinWindow(LocalTime.of(18, 0))).isFalse();
    }

    @Test
    void lifecycleAndConfigurationOnlyOutsideActive() {
        DialerCampaign campaign = new DialerCampaign("PRED", UUID.randomUUID(), UUID.randomUUID(),
                settings(DialerMode.PREDICTIVE, "2.5", "2"));
        Instant now = Instant.parse("2026-10-23T12:00:00Z");
        campaign.activate(now);
        assertThatThrownBy(() -> campaign.configure(settings(DialerMode.PREDICTIVE, "3", "3")))
                .isInstanceOf(ConflictException.class);
        campaign.pause(now);
        campaign.configure(settings(DialerMode.PREDICTIVE, "3", "3"));
        assertThat(campaign.getMaxDialRatio()).isEqualByComparingTo("3");
        campaign.activate(now);
        campaign.finish(now);
        assertThatThrownBy(() -> campaign.activate(now)).isInstanceOf(ConflictException.class);
    }

    @Test
    void rejectsOutOfRangeParameters() {
        UUID queue = UUID.randomUUID();
        assertThatThrownBy(() -> new DialerCampaign("X1", null, queue, settings(DialerMode.PREDICTIVE, "6", null)))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> new DialerCampaign("X1", null, queue, settings(DialerMode.PREDICTIVE, "2", "12")))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> new DialerCampaign("X1", null, queue, new DialerCampaign.Settings("n", null, false,
                DialerMode.PROGRESSIVE, null, null, 0, null, null, null, null))).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> new DialerCampaign("X1", null, queue, new DialerCampaign.Settings("n", null, false,
                DialerMode.PROGRESSIVE, null, null, null, null, null, LocalTime.NOON, null)))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> new DialerCampaign("x", null, queue, settings(DialerMode.PROGRESSIVE, null, null)))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void entryDefersWithoutConsumingAttemptsAndIgnoresLateResults() {
        Instant now = Instant.parse("2026-10-23T12:00:00Z");
        DialerEntry entry = new DialerEntry(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, 1, now);
        entry.defer("OUTSIDE_WINDOW", now.plusSeconds(3600));
        assertThat(entry.getAttempts()).isZero();
        assertThat(entry.getNextAttemptAt()).isEqualTo(now.plusSeconds(3600));
        UUID call = UUID.randomUUID();
        entry.attemptStarted(call, now);
        assertThat(entry.getAttempts()).isEqualTo(1);
        assertThat(entry.attemptFinished(UUID.randomUUID(), CallResult.BUSY, DialerEntryStatus.PENDING, now, now)).isFalse();
        assertThat(entry.attemptFinished(call, CallResult.BUSY, DialerEntryStatus.PENDING, now.plusSeconds(60), now)).isTrue();
        assertThat(entry.getStatus()).isEqualTo(DialerEntryStatus.PENDING);
        entry.exclude("CASE_CLOSED", now);
        assertThat(entry.getStatus()).isEqualTo(DialerEntryStatus.EXCLUDED);
    }
}
