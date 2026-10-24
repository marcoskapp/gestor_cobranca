package br.com.gestorcobranca.assistant.application.port.out;

import java.util.UUID;

/** Serializa os turnos de uma conversa entre instâncias (lock consultivo na transação corrente). */
public interface ConversationLocks {

    void lockConversation(UUID conversationId);
}
