package br.com.gestorcobranca.shared.security;

import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Set;
import java.util.UUID;

/** Coloca a pessoa do credor (ADR 0011) como ator corrente nos testes de unidade dos pontos de acesso. */
public final class CreditorActorFixture {

    private CreditorActorFixture() {
    }

    public static Actor signInCreditor() {
        Actor actor = Actor.creditor(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                Set.copyOf(Permission.CREDITOR_ONLY));
        SecurityContextHolder.getContext().setAuthentication(ActorAuthentication.of(actor));
        return actor;
    }

    public static void signOut() {
        SecurityContextHolder.clearContext();
    }
}
