package br.com.gestorcobranca.communication.domain.model;

import br.com.gestorcobranca.shared.domain.ConflictException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConversationAndMessageTest {

    private static final Instant T0 = Instant.parse("2026-10-20T12:00:00Z");

    private static Conversation whatsapp() {
        return new Conversation(UUID.randomUUID(), UUID.randomUUID(), null, null, ChannelType.WHATSAPP, UUID.randomUUID(),
                null, new Conversation.Contact("+5511987654321", "hash", "(11) *****-4321"), Conversation.Owner.NONE, T0);
    }

    @Test
    void whatsappWindowOpensWithInboundAndLasts24Hours() {
        Conversation c = whatsapp();
        assertThat(c.sessionWindowOpen(T0)).isFalse();
        c.registerInbound(T0);
        assertThat(c.sessionWindowOpen(T0.plus(Duration.ofHours(23)))).isTrue();
        assertThat(c.sessionWindowOpen(T0.plus(Duration.ofHours(24)))).isFalse();
    }

    @Test
    void humanReplyEndsWaitingButCampaignDoesNot() {
        Conversation c = whatsapp();
        c.registerInbound(T0);
        c.registerInbound(T0.plusSeconds(30));
        assertThat(c.getAwaitingSince()).isEqualTo(T0);
        assertThat(c.getUnreadCount()).isEqualTo(2);
        assertThat(c.registerOutbound(SenderType.CAMPAIGN, T0.plusSeconds(60))).isEmpty();
        assertThat(c.registerOutbound(SenderType.USER, T0.plusSeconds(90))).contains(Duration.ofSeconds(90));
        assertThat(c.getAwaitingSince()).isNull();
        assertThat(c.getUnreadCount()).isZero();
    }

    @Test
    void ownerChangesKeepRequiredProfileWhileWaitingAndCloseClearsOwner() {
        Conversation c = whatsapp();
        UUID user = UUID.randomUUID();
        c.changeOwner(Conversation.Owner.queue(null, "SUPERVISOR"), T0);
        assertThat(c.getRequiredProfile()).isEqualTo("SUPERVISOR");
        Conversation.Owner previous = c.changeOwner(Conversation.Owner.user(user, null), T0.plusSeconds(5));
        assertThat(previous.type()).isEqualTo(OwnerType.QUEUE);
        assertThat(c.getRequiredProfile()).isEqualTo("SUPERVISOR");
        assertThat(c.getOwnerSince()).isEqualTo(T0.plusSeconds(5));
        c.close(user, "resolvido", T0.plusSeconds(10));
        assertThat(c.getOwnerType()).isEqualTo(OwnerType.NONE);
        assertThatThrownBy(() -> c.changeOwner(Conversation.Owner.assistant(), T0)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> new Conversation.Owner(OwnerType.USER, null, null, null))
                .hasFieldOrPropertyWithValue("code", "conversation.owner_user_invalid");
    }

    @Test
    void messageStatusNeverRegresses() {
        Message m = Message.outbound(whatsapp(), new Message.Outbound(SenderType.USER, UUID.randomUUID(), null, null, null,
                "Olá", null, null, null), T0);
        assertThat(m.getStatus()).isEqualTo(MessageStatus.QUEUED);
        assertThat(m.applyProviderStatus(MessageStatus.DELIVERED, null, null, T0)).isFalse();
        m.recordAttempt(1);
        m.markSent("wamid.1", UUID.randomUUID(), T0.plusSeconds(1));
        assertThat(m.applyProviderStatus(MessageStatus.READ, null, null, T0.plusSeconds(3))).isTrue();
        assertThat(m.getDeliveredAt()).isEqualTo(T0.plusSeconds(3));
        assertThat(m.applyProviderStatus(MessageStatus.DELIVERED, null, null, T0.plusSeconds(4))).isFalse();
        assertThat(m.applyProviderStatus(MessageStatus.FAILED, "X", null, T0.plusSeconds(5))).isFalse();
        assertThat(m.getStatus()).isEqualTo(MessageStatus.READ);
    }

    @Test
    void providerFailureAfterSentAndBlockedMessages() {
        Message m = Message.outbound(whatsapp(), new Message.Outbound(SenderType.CAMPAIGN, null, null, null, null, "Aviso",
                UUID.randomUUID(), null, "k1"), T0);
        m.markSent("wamid.2", null, T0);
        assertThat(m.applyProviderStatus(MessageStatus.FAILED, "WHATSAPP_131026", "undeliverable", T0.plusSeconds(2)))
                .isTrue();
        assertThat(m.getFailureCode()).isEqualTo("WHATSAPP_131026");

        Message blocked = Message.outbound(whatsapp(), new Message.Outbound(SenderType.SYSTEM, null, null, null, null,
                "Aviso", null, null, null), T0);
        blocked.markBlocked("OUTSIDE_WINDOW", T0);
        assertThat(blocked.getStatus()).isEqualTo(MessageStatus.FAILED);
        assertThat(blocked.getFailureCode()).isEqualTo(Message.CONTACT_DENIED);
        assertThatThrownBy(() -> blocked.markSent("x", null, T0)).isInstanceOf(ConflictException.class);
    }

    @Test
    void inboundMessageAndContentPurge() {
        Message in = Message.inbound(whatsapp(), "wamid.in", "oi", T0);
        assertThat(in.getStatus()).isEqualTo(MessageStatus.RECEIVED);
        assertThat(in.getSenderType()).isEqualTo(SenderType.DEBTOR);
        assertThat(in.purgeContent(T0)).isTrue();
        assertThat(in.getContent()).isNull();
        assertThat(in.purgeContent(T0)).isFalse();
    }
}
