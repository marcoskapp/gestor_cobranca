package br.com.gestorcobranca.shared.security;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActorTest {

    @Test
    void assistantHasOnlyGrantedPermissionsAndConversationCreditors() {
        UUID creditor = UUID.randomUUID();
        Actor assistant = Actor.assistant(UUID.randomUUID(), UUID.randomUUID(), "padrao-v3",
                Set.of(Permission.NEGOTIATION_SIMULATE, Permission.NEGOTIATION_PROPOSE), Set.of(creditor));

        assertThat(assistant.isAssistant()).isTrue();
        assertThat(assistant.name()).isEqualTo("assistant:padrao-v3");
        assertThat(assistant.has(Permission.NEGOTIATION_SIMULATE)).isTrue();
        assertThat(assistant.has(Permission.NEGOTIATION_APPROVE)).isFalse();
        assertThat(assistant.creditorScope().includes(creditor)).isTrue();
        assertThat(assistant.creditorScope().includes(UUID.randomUUID())).isFalse();
        assertThat(assistant.creditorScope().all()).isFalse();
    }

    @Test
    void assistantCannotReceivePlatformPermission() {
        assertThatThrownBy(() -> Actor.assistant(UUID.randomUUID(), UUID.randomUUID(), "x",
                Set.of(Permission.TENANT_MANAGE), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
