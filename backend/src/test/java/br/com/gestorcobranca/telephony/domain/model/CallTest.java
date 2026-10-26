package br.com.gestorcobranca.telephony.domain.model;

import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CallTest {

    static final Instant T0 = Instant.parse("2026-10-23T13:00:00Z");
    static final UUID OPERATOR = UUID.randomUUID();

    static TelephonyAccount account(boolean recording) {
        return new TelephonyAccount(ProviderType.SANDBOX, null, "Sandbox", "+551130000000", recording, recording, null,
                "k".repeat(43), true, T0);
    }

    static Call.Target target() {
        return new Call.Target(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), "+5511987654321", "hash", "(11) *****-4321");
    }

    static Call clickToCall() {
        return Call.outbound(CallOrigin.CLICK_TO_CALL, account(false), target(), OPERATOR, null, null, 1, T0);
    }

    static Call dialer() {
        return Call.outbound(CallOrigin.DIALER, account(false), target(), null, UUID.randomUUID(), UUID.randomUUID(), 2, T0);
    }

    @Test
    void clickToCallAnsweredHasRingTalkAndWrapUpDurations() {
        Call call = clickToCall();
        call.dialing("p-1", UUID.randomUUID(), T0.plusSeconds(1));
        assertThat(call.ringing(T0.plusSeconds(3))).isTrue();
        assertThat(call.answered(T0.plusSeconds(10), AmdResult.HUMAN)).isTrue();
        assertThat(call.getConnectedAt()).isEqualTo(T0.plusSeconds(10));
        assertThat(call.end(CallStatus.COMPLETED, T0.plusSeconds(130), HangupSide.DEBTOR, null)).isTrue();

        assertThat(call.getStatus()).isEqualTo(CallStatus.COMPLETED);
        assertThat(call.getResult()).isEqualTo(CallResult.ANSWERED);
        assertThat(call.getRingSeconds()).isEqualTo(7);
        assertThat(call.getTalkSeconds()).isEqualTo(120);
        assertThat(call.getInteractionState()).isEqualTo(InteractionState.AWAITING_TABULATION);

        call.tabulate("CPC_NEGOTIATING", OPERATOR, T0.plusSeconds(175));
        assertThat(call.getWrapUpSeconds()).isEqualTo(45);
        assertThat(call.getInteractionState()).isEqualTo(InteractionState.PENDING);
        assertThatThrownBy(() -> call.tabulate("PROMISE", OPERATOR, T0.plusSeconds(200)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void outOfOrderAndRepeatedEventsAreStale() {
        Call call = clickToCall();
        call.dialing("p-1", null, T0);
        assertThat(call.answered(T0.plusSeconds(5), null)).isTrue();
        assertThat(call.ringing(T0.plusSeconds(3))).isFalse();
        assertThat(call.answered(T0.plusSeconds(6), null)).isFalse();
        assertThat(call.end(CallStatus.COMPLETED, T0.plusSeconds(20), HangupSide.DEBTOR, null)).isTrue();
        assertThat(call.end(CallStatus.COMPLETED, T0.plusSeconds(21), HangupSide.DEBTOR, null)).isFalse();
        assertThat(call.end(CallStatus.BUSY, T0.plusSeconds(21), HangupSide.PROVIDER, null)).isFalse();
        assertThat(call.getEndedAt()).isEqualTo(T0.plusSeconds(20));
    }

    @Test
    void unansweredDialerCallIsAutomaticWithoutOperator() {
        Call call = dialer();
        call.dialing("p-2", UUID.randomUUID(), T0);
        call.ringing(T0.plusSeconds(2));
        assertThat(call.end(CallStatus.NO_ANSWER, T0.plusSeconds(27), HangupSide.PROVIDER, "TIMEOUT")).isTrue();
        assertThat(call.getResult()).isEqualTo(CallResult.NO_ANSWER);
        assertThat(call.getRingSeconds()).isEqualTo(25);
        assertThat(call.getTalkSeconds()).isZero();
        assertThat(call.getInteractionState()).isEqualTo(InteractionState.PENDING);
        call.autoTabulate("NO_ANSWER", T0.plusSeconds(28));
        assertThat(call.getTabulationCode()).isEqualTo("NO_ANSWER");
        assertThat(call.getWrapUpSeconds()).isNull();
    }

    @Test
    void dialerAnsweredWithoutOperatorIsAbandonedAndMachineIsVoicemail() {
        Call abandoned = dialer();
        abandoned.dialing("p-3", null, T0);
        abandoned.answered(T0.plusSeconds(8), AmdResult.HUMAN);
        abandoned.markAbandoned();
        abandoned.end(CallStatus.COMPLETED, T0.plusSeconds(9), HangupSide.SYSTEM, null);
        assertThat(abandoned.getResult()).isEqualTo(CallResult.ABANDONED);
        assertThat(abandoned.isAbandoned()).isTrue();

        Call machine = dialer();
        machine.dialing("p-4", null, T0);
        machine.answered(T0.plusSeconds(8), AmdResult.MACHINE);
        machine.end(CallStatus.COMPLETED, T0.plusSeconds(9), HangupSide.SYSTEM, null);
        assertThat(machine.getResult()).isEqualTo(CallResult.VOICEMAIL);

        Call connected = dialer();
        connected.dialing("p-5", null, T0);
        connected.answered(T0.plusSeconds(8), AmdResult.HUMAN);
        connected.connect(OPERATOR, T0.plusSeconds(9));
        connected.end(CallStatus.FAILED, T0.plusSeconds(69), HangupSide.PROVIDER, "NETWORK");
        assertThat(connected.getStatus()).isEqualTo(CallStatus.COMPLETED);
        assertThat(connected.getResult()).isEqualTo(CallResult.ANSWERED);
        assertThat(connected.getTalkSeconds()).isEqualTo(60);
        assertThat(connected.getInteractionState()).isEqualTo(InteractionState.AWAITING_TABULATION);
        assertThatThrownBy(connected::markAbandoned).isInstanceOf(ConflictException.class);
    }

    @Test
    void blockedCallNeverReachesTheProvider() {
        Call call = clickToCall();
        call.block("OUTSIDE_WINDOW,HOLIDAY", T0);
        assertThat(call.getStatus()).isEqualTo(CallStatus.FAILED);
        assertThat(call.getFailureCode()).isEqualTo(Call.CONTACT_DENIED);
        assertThat(call.getDenialReasons()).isEqualTo("OUTSIDE_WINDOW,HOLIDAY");
        assertThat(call.wasDialed()).isFalse();
        assertThat(call.getInteractionState()).isEqualTo(InteractionState.SKIPPED);
        assertThatThrownBy(() -> call.dialing("p", null, T0)).isInstanceOf(ConflictException.class);
    }

    @Test
    void cancelledBeforeAnswerAndProviderRejection() {
        Call cancelled = clickToCall();
        cancelled.dialing("p-6", null, T0);
        assertThat(cancelled.end(CallStatus.CANCELLED, T0.plusSeconds(4), HangupSide.OPERATOR, null)).isTrue();
        assertThat(cancelled.getResult()).isEqualTo(CallResult.CANCELLED);
        assertThat(cancelled.getInteractionState()).isEqualTo(InteractionState.AWAITING_TABULATION);

        Call rejected = dialer();
        rejected.failBeforeDialing("SANDBOX_INVALID_NUMBER", "inexistente", T0);
        assertThat(rejected.getResult()).isEqualTo(CallResult.FAILED);
        assertThat(rejected.wasDialed()).isFalse();
        assertThat(rejected.getInteractionState()).isEqualTo(InteractionState.SKIPPED);
    }

    @Test
    void inboundIsIdentifiedConnectedAndRequiresCaseBeforeTabulation() {
        Call call = Call.inbound(account(true), "in-1", "+5511987654321", "hash", "(11) *****-4321", T0);
        assertThat(call.getStatus()).isEqualTo(CallStatus.RINGING);
        assertThat(call.isRecordingAnnounced()).isTrue();
        call.connect(OPERATOR, T0.plusSeconds(4));
        assertThat(call.getStatus()).isEqualTo(CallStatus.ANSWERED);
        call.end(CallStatus.COMPLETED, T0.plusSeconds(64), HangupSide.DEBTOR, null);
        assertThat(call.getResult()).isEqualTo(CallResult.ANSWERED);
        assertThat(call.getRingSeconds()).isEqualTo(4);
        assertThatThrownBy(() -> call.tabulate("CPC_NEGOTIATING", OPERATOR, T0.plusSeconds(70)))
                .isInstanceOf(ConflictException.class);
        call.identify(UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID(), null);
        call.tabulate("CPC_NEGOTIATING", OPERATOR, T0.plusSeconds(70));
        assertThat(call.getWrapUpSeconds()).isEqualTo(6);
    }

    @Test
    void anonymizationRemovesTheNumber() {
        Call call = clickToCall();
        call.anonymizeNumber();
        assertThat(call.getNumber()).isNull();
        assertThat(call.getNumberHash()).isNull();
        assertThat(call.getNumberMasked()).isEqualTo("***");
    }

    @Test
    void invariants() {
        assertThatThrownBy(() -> Call.outbound(CallOrigin.CLICK_TO_CALL, account(false), target(), null, null, null, 1, T0))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("operador");
        assertThatThrownBy(() -> Call.outbound(CallOrigin.DIALER, account(false), target(), null, null, null, 1, T0))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> Call.outbound(CallOrigin.INBOUND, account(false), target(), OPERATOR, null, null, 1, T0))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> new TelephonyAccount(ProviderType.SANDBOX, null, "x", "+551130000000", true, false,
                null, "k".repeat(43), true, T0)).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("aviso de gravação");
    }

    @Test
    void stateMachineTable() {
        assertThat(CallStatus.QUEUED.canTransitionTo(CallStatus.ANSWERED)).isFalse();
        assertThat(CallStatus.DIALING.canTransitionTo(CallStatus.ANSWERED)).isTrue();
        assertThat(CallStatus.RINGING.canTransitionTo(CallStatus.DIALING)).isFalse();
        assertThat(CallStatus.ANSWERED.canTransitionTo(CallStatus.NO_ANSWER)).isFalse();
        for (CallStatus terminal : CallStatus.values()) {
            if (terminal.isTerminal()) {
                for (CallStatus any : CallStatus.values()) {
                    assertThat(terminal.canTransitionTo(any)).isFalse();
                }
            }
        }
    }
}
