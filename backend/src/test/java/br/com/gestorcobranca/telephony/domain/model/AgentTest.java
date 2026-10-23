package br.com.gestorcobranca.telephony.domain.model;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentTest {

    static final Instant T0 = Instant.parse("2026-10-23T12:00:00Z");

    @Test
    void presenceCycleProducesClosedIntervals() {
        Agent agent = new Agent(UUID.randomUUID(), "ramal-201", T0);
        UUID campaign = UUID.randomUUID();
        Agent.PresenceChange login = agent.login(campaign, T0.plusSeconds(10));
        assertThat(login.from()).isEqualTo(PresenceStatus.OFFLINE);
        assertThat(agent.getStatus()).isEqualTo(PresenceStatus.AVAILABLE);
        assertThat(agent.getLastAvailableAt()).isEqualTo(T0.plusSeconds(10));

        UUID call = UUID.randomUUID();
        Agent.PresenceChange reserve = agent.reserve(call, T0.plusSeconds(70));
        assertThat(reserve.from()).isEqualTo(PresenceStatus.AVAILABLE);
        assertThat(reserve.durationSeconds()).isEqualTo(60);
        assertThat(reserve.campaignId()).isEqualTo(campaign);

        assertThat(agent.callEnded(UUID.randomUUID(), true, T0.plusSeconds(100))).isEmpty();
        Agent.PresenceChange ended = agent.callEnded(call, true, T0.plusSeconds(190)).orElseThrow();
        assertThat(ended.from()).isEqualTo(PresenceStatus.ON_CALL);
        assertThat(ended.durationSeconds()).isEqualTo(120);
        assertThat(agent.getStatus()).isEqualTo(PresenceStatus.WRAP_UP);
        assertThatThrownBy(() -> agent.pause(PauseReason.BREAK, T0.plusSeconds(200)))
                .isInstanceOf(ConflictException.class).hasMessageContaining("Tabule");
        assertThatThrownBy(() -> agent.reserve(UUID.randomUUID(), T0.plusSeconds(200)))
                .isInstanceOf(ConflictException.class);

        agent.wrapUpDone(call, T0.plusSeconds(220)).orElseThrow();
        assertThat(agent.getStatus()).isEqualTo(PresenceStatus.AVAILABLE);
        assertThat(agent.getCurrentCallId()).isNull();

        Agent.PresenceChange pause = agent.pause(PauseReason.LUNCH, T0.plusSeconds(300));
        assertThat(pause.toReason()).isEqualTo(PauseReason.LUNCH);
        Agent.PresenceChange back = agent.available(T0.plusSeconds(3900));
        assertThat(back.fromReason()).isEqualTo(PauseReason.LUNCH);
        assertThat(back.durationSeconds()).isEqualTo(3600);

        Agent.PresenceChange logout = agent.logout(false, T0.plusSeconds(4000)).orElseThrow();
        assertThat(logout.campaignId()).isEqualTo(campaign);
        assertThat(agent.getCampaignId()).isNull();
        assertThat(agent.logout(false, T0.plusSeconds(4001))).isEmpty();
    }

    @Test
    void callWithoutTabulationReturnsToAvailableAndOnCallLogoutNeedsForce() {
        Agent agent = new Agent(UUID.randomUUID(), "sip:op@pbx", T0);
        agent.login(null, T0);
        UUID call = UUID.randomUUID();
        agent.reserve(call, T0.plusSeconds(1));
        assertThatThrownBy(() -> agent.logout(false, T0.plusSeconds(2))).isInstanceOf(ConflictException.class);
        agent.callEnded(call, false, T0.plusSeconds(3)).orElseThrow();
        assertThat(agent.getStatus()).isEqualTo(PresenceStatus.AVAILABLE);
        agent.reserve(call, T0.plusSeconds(4));
        assertThat(agent.logout(true, T0.plusSeconds(5))).isPresent();
        assertThat(agent.getStatus()).isEqualTo(PresenceStatus.OFFLINE);
    }

    @Test
    void heartbeatTimeoutAndValidation() {
        Agent agent = new Agent(UUID.randomUUID(), "201", T0);
        assertThat(agent.heartbeatExpired(T0.plusSeconds(1000), Duration.ofSeconds(90))).isFalse();
        agent.login(null, T0);
        agent.heartbeat(T0.plusSeconds(60));
        assertThat(agent.heartbeatExpired(T0.plusSeconds(120), Duration.ofSeconds(90))).isFalse();
        assertThat(agent.heartbeatExpired(T0.plusSeconds(200), Duration.ofSeconds(90))).isTrue();
        assertThatThrownBy(() -> agent.login(null, T0)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> new Agent(UUID.randomUUID(), "x", T0)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> agent.pause(null, T0)).isInstanceOf(BusinessRuleException.class);
        agent.logout(false, T0.plusSeconds(10));
        agent.deactivate(T0.plusSeconds(11));
        assertThatThrownBy(() -> agent.login(null, T0.plusSeconds(12))).isInstanceOf(ConflictException.class);
    }
}
