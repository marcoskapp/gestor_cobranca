package br.com.gestorcobranca.assistant.application.service;

import br.com.gestorcobranca.assistant.application.port.out.PaymentInstructionPort.IssuePixCommand;
import br.com.gestorcobranca.assistant.application.port.out.PaymentInstructionPort.PixInstruction;
import br.com.gestorcobranca.assistant.application.port.out.PaymentInstructionPort;
import br.com.gestorcobranca.assistant.domain.model.AssistantMode;
import br.com.gestorcobranca.assistant.domain.model.AssistantPolicy;
import br.com.gestorcobranca.assistant.domain.model.AssistantSession;
import br.com.gestorcobranca.assistant.domain.model.HandoffReason;
import br.com.gestorcobranca.assistant.domain.model.PixDecision;
import br.com.gestorcobranca.assistant.domain.model.PixDeliveryStatus;
import br.com.gestorcobranca.assistant.domain.model.PolicySettings.PixInConversation;
import br.com.gestorcobranca.assistant.domain.model.PolicySettings;
import br.com.gestorcobranca.assistant.domain.model.SessionMemory;
import br.com.gestorcobranca.assistant.domain.model.TurnOutcome;
import br.com.gestorcobranca.assistant.domain.repository.AssistantTurnRepository;
import br.com.gestorcobranca.assistant.domain.repository.PixRequestRepository;
import br.com.gestorcobranca.collection.application.port.in.DisputeUseCases;
import br.com.gestorcobranca.communication.application.port.in.ConversationUseCases.ConversationView;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.AgreementView;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases.InstallmentView;
import br.com.gestorcobranca.negotiation.application.port.in.AgreementUseCases;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.shared.security.Actor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Ferramentas de Pix: valor só do motor, escopo do devedor da conversa, idempotência e cancelamento. */
class AssistantPixTest {

    private final AgreementUseCases agreements = mock(AgreementUseCases.class);
    private final DisputeUseCases disputes = mock(DisputeUseCases.class);
    private final PaymentInstructionPort port = mock(PaymentInstructionPort.class);
    private final AssistantTurnRepository turns = mock(AssistantTurnRepository.class);
    private final PixRequestRepository requests = mock(PixRequestRepository.class);
    private final AssistantActors actors = mock(AssistantActors.class);
    private AssistantPix pix;
    private TurnContext ctx;
    private UUID creditor;
    private UUID debtor;
    private UUID agreementId;
    private UUID installmentId;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(actors.as(any(), any())).thenAnswer(inv -> ((Supplier<Object>) inv.getArgument(1)).get());
        ObjectProvider<PaymentInstructionPort> payments = mock(ObjectProvider.class);
        when(payments.getIfAvailable()).thenReturn(port);
        when(payments.getObject()).thenReturn(port);
        AssistantCodec codec = new AssistantCodec(new JsonCodec(JsonMapper.builder().build()));
        pix = new AssistantPix(agreements, disputes, payments, turns, requests, actors, codec, mock(AssistantMetrics.class),
                Clock.systemUTC());
        creditor = UUID.randomUUID();
        debtor = UUID.randomUUID();
        agreementId = UUID.randomUUID();
        installmentId = UUID.randomUUID();
        ctx = context(AssistantMode.AUTONOMOUS, new BigDecimal("400.00"), debtor);
        ctx.inputText = "pode mandar o pix";
    }

    private TurnContext context(AssistantMode mode, BigDecimal installment, UUID agreementDebtor) {
        ConversationView conversation = new ConversationView(UUID.randomUUID(), debtor, creditor, null, null, "WHATSAPP",
                UUID.randomUUID(), "***", "OPEN", "ASSISTANT", null, null, null, null, null, null, null, null, null, 0, 0,
                true, null, null, null, Instant.now(), 0, null, null, null);
        PolicySettings settings = new PolicySettings(mode, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null,
                new PixInConversation(true, null, null, null, null)).normalized();
        AssistantPolicy policy = new AssistantPolicy(null, null, "pix", "Pix", settings, "{}");
        TurnContext c = new TurnContext(UUID.randomUUID(), conversation, new PolicyLookup.Effective(policy, settings), mode,
                Actor.assistant(UUID.randomUUID(), policy.getId(), "pix", Set.of(), Set.of(creditor)), UUID.randomUUID(),
                Instant.now());
        c.session = new AssistantSession(conversation.id(), creditor, debtor, null, null, true, "{}");
        c.session.identityVerified(Instant.now());
        c.memory = SessionMemory.EMPTY.withInstallments(List.of(new SessionMemory.InstallmentMemo("I1", agreementId,
                installmentId, 2, LocalDate.now().plusDays(3), installment, "OPEN", "ASSISTANT_AUTONOMOUS")));
        when(agreements.get(agreementId)).thenReturn(agreement(agreementDebtor, installment));
        return c;
    }

    private AgreementView agreement(UUID agreementDebtor, BigDecimal installment) {
        List<InstallmentView> installments = List.of(
                new InstallmentView(UUID.randomUUID(), 1, LocalDate.now().minusDays(30), new BigDecimal("100.00"),
                        new BigDecimal("100.00"), "PAID", Instant.now()),
                new InstallmentView(installmentId, 2, LocalDate.now().plusDays(3), installment, BigDecimal.ZERO, "OPEN", null));
        return new AgreementView(agreementId, "IN_PAYMENT", null, UUID.randomUUID(), creditor, agreementDebtor, null, null,
                UUID.randomUUID(), 1, 0, BigDecimal.ZERO, "WHATSAPP", "ASSISTANT_AUTONOMOUS", List.of(), BigDecimal.TEN,
                BigDecimal.ZERO, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, new BigDecimal("100.00"),
                new BigDecimal("100.00"), installment, List.of(), installments, null, Instant.now(), null, null, null, null,
                null, 0, null, null, false, 0);
    }

    private static PixInstruction instruction(BigDecimal amount, Instant expiresAt, boolean reused) {
        return new PixInstruction(UUID.randomUUID(), "PIX_IMMEDIATE", "PENDING", null, null, null, null, 2, amount,
                expiresAt, null, Instant.now(), null, reused);
    }

    @Test
    void issuesExactlyTheInstallmentAmountWithPerTurnIdempotency() {
        when(port.issueImmediatePix(any())).thenReturn(instruction(new BigDecimal("400.00"),
                Instant.now().plus(Duration.ofHours(1)), false));
        AssistantToolbox.ToolOutcome out = pix.request(ctx, "I1");
        ArgumentCaptor<IssuePixCommand> cmd = ArgumentCaptor.forClass(IssuePixCommand.class);
        verify(port).issueImmediatePix(cmd.capture());
        assertThat(cmd.getValue().amount()).isEqualByComparingTo("400.00");
        assertThat(cmd.getValue().installmentId()).isEqualTo(installmentId);
        assertThat(cmd.getValue().idempotencyKey()).isEqualTo("assistant:pix:" + ctx.token + ":" + installmentId);
        assertThat(cmd.getValue().expiresInSeconds()).isEqualTo(3600);
        assertThat(out.terminal()).isNull();
        assertThat(out.resultJson()).contains("\"issued\":true").doesNotContain("br.gov.bcb.pix");
        assertThat(ctx.pixRequests).singleElement().satisfies(r -> {
            assertThat(r.getDecision()).isEqualTo(PixDecision.ISSUED);
            assertThat(r.getDeliveryStatus()).isEqualTo(PixDeliveryStatus.AWAITING);
            assertThat(r.getCriteria()).contains("EXPLICIT_REQUEST");
        });
        // o valor da parcela vira fato citável na resposta; nada mais
        assertThat(ctx.memory.facts().allowsAmount(new BigDecimal("400.00"))).isTrue();
        assertThat(ctx.memory.facts().allowsAmount(new BigDecimal("401.00"))).isFalse();
    }

    @Test
    void unknownInstallmentReferenceIsRejectedBeforeAnyCall() {
        assertThatThrownBy(() -> pix.request(ctx, "I9")).isInstanceOf(AssistantToolbox.ArgumentException.class)
                .hasMessage("unknown_installment");
        verifyNoInteractions(port);
    }

    @Test
    void agreementOfAnotherDebtorIsDeniedWithoutPayment() {
        ctx = context(AssistantMode.AUTONOMOUS, new BigDecimal("400.00"), UUID.randomUUID());
        ctx.inputText = "pode mandar o pix";
        AssistantToolbox.ToolOutcome out = pix.request(ctx, "I1");
        assertThat(out.resultJson()).contains("OTHER_PARTY");
        assertThat(out.terminal()).isNull();
        verifyNoInteractions(port);
    }

    @Test
    void complaintInTheSessionDeniesAndHandsOff() {
        when(turns.handoffReasonsSince(any(), any())).thenReturn(List.of("TURN_LIMIT", "COMPLAINT"));
        AssistantToolbox.ToolOutcome out = pix.request(ctx, "I1");
        assertThat(out.resultJson()).contains("CONVERSATION_RISK");
        assertThat(out.terminal().reason()).isEqualTo(HandoffReason.PAYMENT_ASSISTANCE);
        assertThat(out.terminal().outcome()).isEqualTo(TurnOutcome.HANDED_OFF);
        verifyNoInteractions(port);
    }

    @Test
    void operationalHandoffsDoNotCountAsRisk() {
        when(turns.handoffReasonsSince(any(), any())).thenReturn(List.of("TURN_LIMIT", "FORMALIZATION_APPROVAL"));
        when(port.issueImmediatePix(any())).thenReturn(instruction(new BigDecimal("400.00"),
                Instant.now().plus(Duration.ofHours(1)), false));
        assertThat(pix.request(ctx, "I1").resultJson()).contains("\"issued\":true");
    }

    @Test
    void amountAboveTheLimitHandsOff() {
        ctx = context(AssistantMode.AUTONOMOUS, new BigDecimal("5000.01"), debtor);
        ctx.inputText = "quero pagar agora";
        AssistantToolbox.ToolOutcome out = pix.request(ctx, "I1");
        assertThat(out.resultJson()).contains("AMOUNT_ABOVE_LIMIT");
        assertThat(out.terminal().reason()).isEqualTo(HandoffReason.PAYMENT_ASSISTANCE);
    }

    @Test
    void withoutExplicitRequestTheModelIsToldToAskAndNothingIsTransferred() {
        ctx.inputText = "e o pix?";
        AssistantToolbox.ToolOutcome out = pix.request(ctx, "I1");
        assertThat(out.resultJson()).contains("NO_EXPLICIT_REQUEST");
        assertThat(out.terminal()).isNull();
        verifyNoInteractions(port);
    }

    @Test
    void reusedInstructionCloseToExpiryIsReplaced() {
        PixInstruction stale = instruction(new BigDecimal("400.00"), Instant.now().plus(Duration.ofMinutes(3)), true);
        PixInstruction fresh = instruction(new BigDecimal("400.00"), Instant.now().plus(Duration.ofHours(1)), false);
        when(port.issueImmediatePix(any())).thenReturn(stale, fresh);
        pix.request(ctx, "I1");
        verify(port).cancel(eq(stale.instructionId()), anyString());
        verify(port, times(2)).issueImmediatePix(any());
        assertThat(ctx.pixRequests.getFirst().getInstructionId()).isEqualTo(fresh.instructionId());
    }

    @Test
    void turnWithoutReplyCancelsTheInstructionCreatedInTheTurn() {
        when(port.issueImmediatePix(any())).thenReturn(instruction(new BigDecimal("400.00"),
                Instant.now().plus(Duration.ofHours(1)), false));
        pix.request(ctx, "I1");
        ctx.end(TurnOutcome.HANDED_OFF, HandoffReason.GUARDRAIL, null);
        pix.settle(ctx);
        verify(port).cancel(eq(ctx.pixRequests.getFirst().getInstructionId()), anyString());
        assertThat(ctx.pixRequests.getFirst().getDeliveryStatus()).isEqualTo(PixDeliveryStatus.CANCELLED);
    }

    @Test
    void reusedInstructionFromAnotherChannelIsNotCancelledWithTheTurn() {
        when(port.issueImmediatePix(any())).thenReturn(instruction(new BigDecimal("400.00"),
                Instant.now().plus(Duration.ofHours(5)), true));
        pix.request(ctx, "I1");
        ctx.end(TurnOutcome.SEND_BLOCKED, null, null);
        pix.settle(ctx);
        verify(port, never()).cancel(any(), anyString());
        assertThat(ctx.pixRequests.getFirst().getDeliveryStatus()).isEqualTo(PixDeliveryStatus.CANCELLED);
    }

    @Test
    void shadowModeOnlySuggests() {
        ctx = context(AssistantMode.SHADOW, new BigDecimal("400.00"), debtor);
        ctx.inputText = "pode mandar o pix";
        AssistantToolbox.ToolOutcome out = pix.request(ctx, "I1");
        assertThat(out.resultJson()).contains("\"suggested\":true");
        assertThat(ctx.pixSuggestion).contains("gere pela tela");
        verifyNoInteractions(port);
    }
}
