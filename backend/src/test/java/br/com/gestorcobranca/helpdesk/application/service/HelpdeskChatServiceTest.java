package br.com.gestorcobranca.helpdesk.application.service;

import br.com.gestorcobranca.helpdesk.application.HelpdeskProperties;
import br.com.gestorcobranca.helpdesk.application.port.in.HelpdeskChatUseCases.AskCommand;
import br.com.gestorcobranca.helpdesk.application.port.in.HelpdeskExceptions;
import br.com.gestorcobranca.helpdesk.application.port.out.HelpdeskModelPort;
import br.com.gestorcobranca.helpdesk.application.port.out.HelpdeskPrompt;
import br.com.gestorcobranca.helpdesk.application.port.out.KnowledgeBase;
import br.com.gestorcobranca.helpdesk.domain.model.ChatConversation;
import br.com.gestorcobranca.helpdesk.domain.repository.ChatMessageRepository.UsageTotals;
import br.com.gestorcobranca.identity.application.port.in.MyAccountUseCases;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.json.JsonCodec;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CreditorScope;
import br.com.gestorcobranca.shared.security.CurrentActor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Antes de qualquer chamada ao modelo: validação, indisponibilidade sem provedor e limites de taxa, do dia (perguntas
 * e custo) e da conversa (perguntas e custo). Dublês no lugar do banco e do modelo.
 */
class HelpdeskChatServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-26T15:00:00Z"), ZoneOffset.UTC);

    private final ChatStore store = mock(ChatStore.class);
    private final HelpdeskModelPort model = mock(HelpdeskModelPort.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<HelpdeskModelPort> models = mock(ObjectProvider.class);
    private final CurrentActor currentActor = mock(CurrentActor.class);
    private final Actor actor = new Actor(Actor.ActorType.USER, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            "Ana", Set.of(), CreditorScope.ALL);
    private HelpdeskProperties properties;
    private HelpdeskChatService service;

    @BeforeEach
    void setUp() {
        properties = new HelpdeskProperties(new HelpdeskProperties.Limits(null, null, null, null, null, null,
                new BigDecimal("0.10"), new BigDecimal("0.50"), 3, 2, 4), null, null, null, null);
        when(currentActor.get()).thenReturn(actor);
        when(models.getIfAvailable()).thenReturn(model);
        when(store.usageToday(actor.id())).thenReturn(new UsageTotals(0, BigDecimal.ZERO));
        HelpdeskMetrics metrics = new HelpdeskMetrics(new SimpleMeterRegistry());
        service = new HelpdeskChatService(properties, models, mock(KnowledgeBase.class), mock(HelpdeskPrompt.class),
                store, mock(HelpdeskToolbox.class), mock(ChatContextBuilder.class), new HelpdeskRateLimiter(properties, CLOCK),
                metrics, mock(HelpdeskTicketService.class), currentActor, mock(MyAccountUseCases.class),
                new JsonCodec(JsonMapper.builder().build()), CLOCK);
    }

    @Test
    void emptyOrLongQuestionIsRejected() {
        assertThatThrownBy(() -> service.ask(new AskCommand(null, null, "  "))).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> service.ask(new AskCommand(null, null, "x".repeat(2001))))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("2000");
    }

    @Test
    void withoutProviderTheChatIsUnavailableAndNothingIsRecorded() {
        when(models.getIfAvailable()).thenReturn(null);
        assertThatThrownBy(() -> service.ask(new AskCommand(null, null, "oi")))
                .isInstanceOfSatisfying(HelpdeskExceptions.Unavailable.class,
                        e -> assertThat(e.code()).isEqualTo("helpdesk.unavailable"));
        verifyNoInteractions(store);
    }

    @Test
    void perMinuteRateIsLimited() {
        when(store.conversationFor(any(), any(), any())).thenThrow(new IllegalStateException("parou aqui"));
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> service.ask(new AskCommand(null, null, "oi"))).hasMessage("parou aqui");
        }
        assertThatThrownBy(() -> service.ask(new AskCommand(null, null, "oi")))
                .isInstanceOfSatisfying(HelpdeskExceptions.LimitReached.class,
                        e -> assertThat(e.code()).isEqualTo("helpdesk.limit_rate"));
    }

    @Test
    void dailyQuestionsAndCostAreLimited() {
        when(store.usageToday(actor.id())).thenReturn(new UsageTotals(3, BigDecimal.ZERO));
        assertThatThrownBy(() -> service.ask(new AskCommand(null, null, "oi")))
                .isInstanceOfSatisfying(HelpdeskExceptions.LimitReached.class,
                        e -> assertThat(e.code()).isEqualTo("helpdesk.limit_daily"));
        when(store.usageToday(actor.id())).thenReturn(new UsageTotals(0, new BigDecimal("0.50")));
        assertThatThrownBy(() -> service.ask(new AskCommand(null, null, "oi")))
                .isInstanceOf(HelpdeskExceptions.LimitReached.class);
        verify(store, never()).appendQuestion(any(), any(), any(), any());
    }

    @Test
    void conversationTurnsAndCostAreLimited() {
        ChatConversation full = new ChatConversation(actor.id(), null, CLOCK.instant());
        for (int i = 0; i < 4; i++) {
            full.recordTurn(null, 1, 1, BigDecimal.ZERO, "USD", CLOCK.instant());
        }
        UUID id = full.getId();
        when(store.conversationFor(eq(id), any(), any())).thenReturn(full);
        assertThatThrownBy(() -> service.ask(new AskCommand(id, null, "oi")))
                .isInstanceOfSatisfying(HelpdeskExceptions.LimitReached.class,
                        e -> assertThat(e.code()).isEqualTo("helpdesk.limit_conversation"));
        ChatConversation expensive = new ChatConversation(actor.id(), null, CLOCK.instant());
        expensive.recordTurn(null, 1, 1, new BigDecimal("0.10"), "USD", CLOCK.instant());
        when(store.conversationFor(eq(expensive.getId()), any(), any())).thenReturn(expensive);
        assertThatThrownBy(() -> service.ask(new AskCommand(expensive.getId(), null, "oi")))
                .isInstanceOf(HelpdeskExceptions.LimitReached.class);
        verify(store, never()).appendQuestion(any(), any(), any(), any());
        verifyNoInteractions(model);
    }
}
