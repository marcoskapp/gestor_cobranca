package br.com.gestorcobranca.communication.application.service;

import br.com.gestorcobranca.identity.application.port.in.UserDirectory.UserSummary;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Regra única de quem pode receber uma conversa (lista e atribuição). */
class ConversationAssigneesTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    private static UserSummary user(boolean active, boolean all, Set<UUID> creditors, String... permissions) {
        return new UserSummary(UUID.randomUUID(), "Fulano", active, false, all, creditors, Set.of(permissions), Set.of());
    }

    @Test
    void withCaseTheOperatorAnswersAndWorksTheCase() {
        assertThat(ConversationAssignees.isEligible(user(true, false, Set.of(A), "INBOX_VIEW", "MESSAGE_SEND", "CASE_WORK"),
                A, true)).isTrue();
        assertThat(ConversationAssignees.isEligible(user(true, false, Set.of(A), "INBOX_VIEW", "MESSAGE_SEND"), A, true))
                .as("sem CASE_WORK").isFalse();
        assertThat(ConversationAssignees.isEligible(user(true, false, Set.of(A), "CASE_WORK", "MESSAGE_SEND"), A, true))
                .as("sem INBOX_VIEW").isFalse();
        assertThat(ConversationAssignees.isEligible(user(true, false, Set.of(A), "CASE_WORK", "INBOX_VIEW"), A, true))
                .as("sem MESSAGE_SEND").isFalse();
        assertThat(ConversationAssignees.isEligible(user(true, false, Set.of(B), "INBOX_VIEW", "MESSAGE_SEND", "CASE_WORK"),
                A, true)).as("fora do credor do caso").isFalse();
        assertThat(ConversationAssignees.isEligible(user(false, true, Set.of(), "INBOX_VIEW", "MESSAGE_SEND", "CASE_WORK"),
                A, true)).as("inativo").isFalse();
    }

    @Test
    void withoutCaseTheCreditorOfTheConversationDecides() {
        UserSummary inboxB = user(true, false, Set.of(B), "INBOX_VIEW", "MESSAGE_SEND");
        assertThat(ConversationAssignees.isEligible(inboxB, B, false)).as("CASE_WORK não é exigido sem caso").isTrue();
        assertThat(ConversationAssignees.isEligible(inboxB, A, false)).isFalse();
        // conversa sem credor (contato não identificado): só quem tem todos os credores
        assertThat(ConversationAssignees.isEligible(inboxB, null, false)).isFalse();
        assertThat(ConversationAssignees.isEligible(user(true, true, Set.of(), "INBOX_VIEW", "MESSAGE_SEND"), null, false))
                .isTrue();
        assertThat(ConversationAssignees.isEligible(null, null, false)).isFalse();
    }
}
