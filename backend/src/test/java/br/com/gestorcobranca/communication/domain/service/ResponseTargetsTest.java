package br.com.gestorcobranca.communication.domain.service;

import br.com.gestorcobranca.communication.domain.model.ChannelType;
import br.com.gestorcobranca.communication.domain.model.ResponseTarget;
import br.com.gestorcobranca.communication.domain.model.SlaStatus;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResponseTargetsTest {

    private static final Instant NOW = Instant.parse("2026-10-23T15:00:00Z");

    @Test
    void defaultsAreMinutesForInstantChannelsAndHoursForEmail() {
        ResponseTargets t = ResponseTargets.DEFAULTS;
        assertThat(t.status(ChannelType.WHATSAPP, NOW.minus(Duration.ofMinutes(14)), NOW)).contains(SlaStatus.OK);
        assertThat(t.status(ChannelType.SMS, NOW.minus(Duration.ofMinutes(15)), NOW)).contains(SlaStatus.WARNING);
        assertThat(t.status(ChannelType.WHATSAPP, NOW.minus(Duration.ofMinutes(60)), NOW)).contains(SlaStatus.BREACHED);
        assertThat(t.status(ChannelType.EMAIL, NOW.minus(Duration.ofHours(5)), NOW)).contains(SlaStatus.WARNING);
        assertThat(t.status(ChannelType.EMAIL, NOW.minus(Duration.ofHours(24)), NOW)).contains(SlaStatus.BREACHED);
        assertThat(t.status(ChannelType.EMAIL, null, NOW)).isEmpty();
    }

    @Test
    void configuredChannelsReplaceOnlyTheirDefaults() {
        ResponseTargets t = ResponseTargets.of(Map.of(ChannelType.WHATSAPP,
                new ResponseTargets.Target(Duration.ofMinutes(30), Duration.ofMinutes(120))));
        assertThat(t.classify(ChannelType.WHATSAPP, Duration.ofMinutes(61))).isEqualTo(SlaStatus.WARNING);
        assertThat(t.classify(ChannelType.SMS, Duration.ofMinutes(61))).isEqualTo(SlaStatus.BREACHED);
        assertThat(t.target(ChannelType.EMAIL).breach()).isEqualTo(Duration.ofHours(24));
    }

    @Test
    void targetValidatesBounds() {
        assertThatThrownBy(() -> new ResponseTarget(ChannelType.SMS, 30, 30)).isInstanceOf(BusinessRuleException.class)
                .extracting("code").isEqualTo("response_target.warning_after_breach");
        assertThatThrownBy(() -> new ResponseTarget(ChannelType.SMS, 0, 30)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> new ResponseTarget(ChannelType.SMS, 10, ResponseTarget.MAX_MINUTES + 1))
                .isInstanceOf(BusinessRuleException.class);
        assertThat(new ResponseTarget(ChannelType.SMS, 10, 30).getBreachMinutes()).isEqualTo(30);
    }
}
