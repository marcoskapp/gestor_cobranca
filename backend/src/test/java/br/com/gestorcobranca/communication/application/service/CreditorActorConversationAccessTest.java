package br.com.gestorcobranca.communication.application.service;

import br.com.gestorcobranca.communication.domain.model.Conversation;
import br.com.gestorcobranca.communication.domain.repository.ConversationRepository;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.logging.access.AccessLogger;
import br.com.gestorcobranca.shared.security.AccessScope;
import br.com.gestorcobranca.shared.security.CreditorActorFixture;
import br.com.gestorcobranca.shared.security.CurrentActor;
import br.com.gestorcobranca.shared.security.ScopeAccessDeniedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** A pessoa do credor (ADR 0011) não trabalha conversas nem envia mensagens pelo canal da assessoria. */
class CreditorActorConversationAccessTest {

    private final CurrentActor currentActor = new CurrentActor();
    private final ConversationAccess access = new ConversationAccess(mock(ConversationRepository.class),
            new AccessScope(currentActor, mock(AccessLogger.class)), currentActor);

    @BeforeEach
    void signIn() {
        CreditorActorFixture.signInCreditor();
    }

    @AfterEach
    void signOut() {
        CreditorActorFixture.signOut();
    }

    @Test
    void credorNaoTrabalhaConversaNemEnviaMensagem() {
        assertThatThrownBy(() -> access.requireWorkable(mock(Conversation.class))).isInstanceOf(ScopeAccessDeniedException.class);
        assertThatThrownBy(() -> access.senderOf(null)).isInstanceOf(ConflictException.class)
                .hasMessageContaining("credor");
    }
}
