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
        call.dialRequested(UUID.randomUUID(), T0.plusSeconds(1));
        call.providerAccepted("p-1");
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
        call.dialRequested(null, T0);
        call.providerAccepted("p-1");
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
        call.dialRequested(UUID.randomUUID(), T0);
        call.providerAccepted("p-2");
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
        abandoned.dialRequested(null, T0);
        abandoned.providerAccepted("p-3");
        abandoned.answered(T0.plusSeconds(8), AmdResult.HUMAN);
        abandoned.markAbandoned();
        abandoned.end(CallStatus.COMPLETED, T0.plusSeconds(9), HangupSide.SYSTEM, null);
        assertThat(abandoned.getResult()).isEqualTo(CallResult.ABANDONED);
        assertThat(abandoned.isAbandoned()).isTrue();

        Call machine = dialer();
        machine.dialRequested(null, T0);
        machine.providerAccepted("p-4");
        machine.answered(T0.plusSeconds(8), AmdResult.MACHINE);
        machine.end(CallStatus.COMPLETED, T0.plusSeconds(9), HangupSide.SYSTEM, null);
        assertThat(machine.getResult()).isEqualTo(CallResult.VOICEMAIL);

        Call connected = dialer();
        connected.dialRequested(null, T0);
        connected.providerAccepted("p-5");
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
        assertThatThrownBy(() -> call.dialRequested(null, T0)).isInstanceOf(ConflictException.class);
    }

    @Test
    void cancelledBeforeAnswerAndProviderRejection() {
        Call cancelled = clickToCall();
        cancelled.dialRequested(null, T0);
        cancelled.providerAccepted("p-6");
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
    void dialRequestComesBeforeTheProviderAnswerAndEventsMayArriveFirst() {
        Call call = dialer();
        UUID attempt = UUID.randomUUID();
        call.dialRequested(attempt, T0);
        assertThat(call.getStatus()).isEqualTo(CallStatus.DIALING);
        assertThat(call.isAwaitingProvider()).isTrue();
        assertThat(call.getProviderCallId()).isNull();
        assertThat(call.getContactAttemptId()).isEqualTo(attempt);
        assertThat(call.wasDialed()).isTrue();
        assertThatThrownBy(() -> call.dialRequested(attempt, T0)).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "call.not_queued");

        // toque e atendimento chegam antes da resposta do pedido
        assertThat(call.ringing(T0.plusSeconds(2))).isTrue();
        assertThat(call.answered(T0.plusSeconds(5), AmdResult.HUMAN)).isTrue();
        assertThat(call.isAwaitingProvider()).isFalse();
        assertThat(call.providerAccepted("p-7")).isTrue();
        assertThat(call.getProviderCallId()).isEqualTo("p-7");
        assertThat(call.getStatus()).isEqualTo(CallStatus.ANSWERED);
        // o evento já tinha informado o mesmo id: repetir não muda nada; outro id é conflito
        assertThat(call.providerAccepted("p-7")).isFalse();
        assertThatThrownBy(() -> call.providerAccepted("p-8")).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "call.provider_id_conflict");
    }

    @Test
    void providerAcceptanceRequiresTheDialRequest() {
        Call call = clickToCall();
        assertThatThrownBy(() -> call.providerAccepted("p-1")).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "call.dial_not_requested");
        call.dialRequested(null, T0);
        assertThatThrownBy(() -> call.providerAccepted(" ")).isInstanceOf(BusinessRuleException.class);
        Call inbound = Call.inbound(account(false), "in-9", "+5511987654321", "hash", "(11) *****-4321", T0);
        assertThatThrownBy(() -> inbound.dialRequested(null, T0)).isInstanceOf(ConflictException.class);
    }

    @Test
    void providerRejectionAfterTheRequestEndsAsNotDialed() {
        Call call = dialer();
        UUID attempt = UUID.randomUUID();
        call.dialRequested(attempt, T0);
        call.providerRejected("PROVIDER_UNAVAILABLE", "Provedor indisponível neste ciclo", T0.plusSeconds(3));
        assertThat(call.getStatus()).isEqualTo(CallStatus.FAILED);
        assertThat(call.getResult()).isEqualTo(CallResult.FAILED);
        assertThat(call.getFailureCode()).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(call.wasDialed()).isFalse();
        assertThat(call.getRingSeconds()).isZero();
        assertThat(call.getInteractionState()).isEqualTo(InteractionState.SKIPPED);
        assertThat(call.getContactAttemptId()).isEqualTo(attempt);
        assertThatThrownBy(() -> call.providerRejected("X", null, T0)).isInstanceOf(ConflictException.class)
                .hasFieldOrPropertyWithValue("code", "call.not_awaiting_provider");

        Call accepted = clickToCall();
        accepted.dialRequested(null, T0);
        accepted.providerAccepted("p-9");
        assertThatThrownBy(() -> accepted.providerRejected("X", null, T0)).isInstanceOf(ConflictException.class);
    }

    @Test
    void fallbackStillAppliesWhileTheRequestHasNoAnswer() {
        Call call = clickToCall();
        TelephonyAccount primary = account(false);
        call.dialRequested(UUID.randomUUID(), T0);
        TelephonyAccount fallback = account(true);
        call.switchToFallback(fallback);
        assertThat(call.getAccountId()).isEqualTo(fallback.getId());
        assertThat(call.isRecordingRequested()).isTrue();
        assertThat(call.isAwaitingProvider()).isTrue();
        call.providerAccepted("p-10");
        assertThatThrownBy(() -> call.switchToFallback(primary)).isInstanceOf(ConflictException.class);
    }

    @Test
    void failedBridgeUndoesTheConnection() {
        Call dialed = dialer();
        dialed.dialRequested(UUID.randomUUID(), T0);
        dialed.providerAccepted("p-11");
        dialed.answered(T0.plusSeconds(5), AmdResult.HUMAN);
        dialed.connect(OPERATOR, T0.plusSeconds(6));
        dialed.bridgeFailed();
        assertThat(dialed.getOperatorId()).isNull();
        assertThat(dialed.getConnectedAt()).isNull();
        assertThat(dialed.isAbandoned()).isTrue();
        dialed.end(CallStatus.COMPLETED, T0.plusSeconds(7), HangupSide.SYSTEM, null);
        assertThat(dialed.getResult()).isEqualTo(CallResult.ABANDONED);
        assertThat(dialed.getTalkSeconds()).isZero();
        assertThatThrownBy(dialed::bridgeFailed).isInstanceOf(ConflictException.class);

        Call inbound = Call.inbound(account(false), "in-2", "+5511987654321", "hash", "(11) *****-4321", T0);
        inbound.connect(OPERATOR, T0.plusSeconds(3));
        inbound.bridgeFailed();
        assertThat(inbound.getStatus()).isEqualTo(CallStatus.RINGING);
        assertThat(inbound.getAnsweredAt()).isNull();
        assertThat(inbound.end(CallStatus.NO_ANSWER, T0.plusSeconds(4), HangupSide.SYSTEM, "NO_AGENT")).isTrue();
        assertThat(inbound.getResult()).isEqualTo(CallResult.NO_ANSWER);

        Call clickToCall = clickToCall();
        clickToCall.dialRequested(null, T0);
        clickToCall.providerAccepted("p-12");
        clickToCall.answered(T0.plusSeconds(2), null);
        assertThatThrownBy(clickToCall::bridgeFailed).isInstanceOf(ConflictException.class);
    }

    @Test
    void bridgeRequestIsMarkedOncePerConnectionAndClearedWhenTheConnectionIsUndone() {
        Call dialed = dialer();
        dialed.dialRequested(UUID.randomUUID(), T0);
        dialed.providerAccepted("p-13");
        dialed.answered(T0.plusSeconds(5), AmdResult.HUMAN);
        // sem operador conectado não há conexão a pedir
        assertThatThrownBy(() -> dialed.bridgeRequested(T0.plusSeconds(5))).isInstanceOf(ConflictException.class);

        dialed.connect(OPERATOR, T0.plusSeconds(6));
        assertThat(dialed.isBridgeRequested()).isFalse();
        dialed.bridgeRequested(T0.plusSeconds(6));
        assertThat(dialed.isBridgeRequested()).isTrue();
        assertThat(dialed.getBridgeRequestedAt()).isEqualTo(T0.plusSeconds(6));
        // a marca indica que o pedido pode ter chegado ao provedor: não se pede de novo
        assertThatThrownBy(() -> dialed.bridgeRequested(T0.plusSeconds(7))).isInstanceOf(ConflictException.class);

        dialed.bridgeFailed();
        assertThat(dialed.isBridgeRequested()).isFalse();
        assertThatThrownBy(() -> dialed.bridgeRequested(T0.plusSeconds(8))).isInstanceOf(ConflictException.class);
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
