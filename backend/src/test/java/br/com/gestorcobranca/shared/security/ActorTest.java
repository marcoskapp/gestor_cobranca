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
    void creditorSeesOnlyItsCreditorAndIsNeverTrustedAutomation() {
        UUID creditor = UUID.randomUUID();
        Actor actor = Actor.creditor(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), creditor,
                Set.of(Permission.CREDITOR_PORTAL_OVERVIEW, Permission.CREDITOR_STATEMENT_RESPOND));

        assertThat(actor.isCreditor()).isTrue();
        assertThat(actor.isUser()).isFalse();
        assertThat(actor.bypassesStaffRules()).isFalse();
        assertThat(actor.creditorScope().includes(creditor)).isTrue();
        assertThat(actor.creditorScope().includes(UUID.randomUUID())).isFalse();
        assertThat(actor.creditorScope().all()).isFalse();
        // nenhuma permissão interna nem de plataforma: as leituras do portal são métodos próprios dos casos de uso
        for (Permission internal : Set.of(Permission.TENANT_MANAGE, Permission.SETTLEMENT_VIEW, Permission.SETTLEMENT_CLOSE,
                Permission.PAYMENT_REGISTER_EXTERNAL, Permission.DISPUTE_WORK, Permission.CASE_VIEW)) {
            assertThatThrownBy(() -> Actor.creditor(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), creditor,
                    Set.of(Permission.CREDITOR_PORTAL_OVERVIEW, internal))).as(internal.name())
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void bypassesStaffRulesKeepsTheMeaningOfNonUserForTheOtherActors() {
        UUID tenant = UUID.randomUUID();
        assertThat(Actor.system(tenant, "job").bypassesStaffRules()).isTrue();
        assertThat(new Actor(Actor.ActorType.INTEGRATION, UUID.randomUUID(), tenant, null, "i", Set.of(), null)
                .bypassesStaffRules()).isTrue();
        assertThat(new Actor(Actor.ActorType.DEBTOR, UUID.randomUUID(), tenant, UUID.randomUUID(), "d", Set.of(), null)
                .bypassesStaffRules()).isTrue();
        assertThat(new Actor(Actor.ActorType.USER, UUID.randomUUID(), tenant, UUID.randomUUID(), "u", Set.of(), null)
                .bypassesStaffRules()).isFalse();
        assertThat(Actor.assistant(tenant, UUID.randomUUID(), "p", Set.of(), Set.of()).bypassesStaffRules()).isTrue();
    }

    @Test
    void onlyTheCreditorHasCreditorPortalPermissionsAndExactlyOneCreditor() {
        UUID tenant = UUID.randomUUID();
        assertThatThrownBy(() -> new Actor(Actor.ActorType.USER, UUID.randomUUID(), tenant, UUID.randomUUID(), "u",
                Set.of(Permission.CREDITOR_PORTAL_OVERVIEW), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Actor(Actor.ActorType.CREDITOR, UUID.randomUUID(), tenant, UUID.randomUUID(), "c",
                Set.of(Permission.CREDITOR_PORTAL_OVERVIEW), CreditorScope.ALL)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Actor.system(tenant, "job").has(Permission.CREDITOR_STATEMENT_RESPOND)).isFalse();
        assertThat(Actor.system(tenant, "job").has(Permission.SETTLEMENT_CLOSE)).isTrue();
    }

    /** Armadilha: um tipo de ator novo exige revisar as regras que dependem do tipo (ADR 0011). */
    @Test
    void newActorTypeRequiresReview() {
        assertThat(java.util.EnumSet.allOf(Actor.ActorType.class)).as(
                "tipo de ator novo: revisar bypassesStaffRules, SecurityConfig.INTERNAL_ONLY, CaseAccess/ConversationAccess/"
                        + "LegalAccess, ConversationAccess.senderOf, AuthorityService.mine e as restrições check de actor_type")
                .containsExactlyInAnyOrder(Actor.ActorType.USER, Actor.ActorType.SYSTEM, Actor.ActorType.DEBTOR,
                        Actor.ActorType.INTEGRATION, Actor.ActorType.ASSISTANT, Actor.ActorType.CREDITOR);
    }

    @Test
    void assistantCannotReceiveCreditorPortalPermission() {
        assertThatThrownBy(() -> Actor.assistant(UUID.randomUUID(), UUID.randomUUID(), "x",
                Set.of(Permission.CREDITOR_PORTAL_OVERVIEW), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void assistantCannotReceivePlatformPermission() {
        assertThatThrownBy(() -> Actor.assistant(UUID.randomUUID(), UUID.randomUUID(), "x",
                Set.of(Permission.TENANT_MANAGE), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
