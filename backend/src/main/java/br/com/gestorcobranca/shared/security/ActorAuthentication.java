package br.com.gestorcobranca.shared.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.io.Serial;
import java.util.Collection;
import java.util.List;

/**
 * Autenticação Spring Security cujo principal é um {@link Actor}. As permissões do ator viram
 * authorities, permitindo {@code @PreAuthorize("hasAuthority('CASE_WORK')")}.
 * <p>
 * É a única fonte de verdade do contexto de execução: tenant, usuário, sessão e escopo de
 * credores são lidos daqui por {@link CurrentActor}.
 */
public final class ActorAuthentication extends AbstractAuthenticationToken {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient Actor actor;
    private final String credentials;

    public ActorAuthentication(Actor actor, String credentials) {
        super(toAuthorities(actor));
        this.actor = actor;
        this.credentials = credentials;
        setAuthenticated(true);
    }

    public static ActorAuthentication of(Actor actor) {
        return new ActorAuthentication(actor, null);
    }

    private static Collection<? extends GrantedAuthority> toAuthorities(Actor actor) {
        List<GrantedAuthority> authorities = new java.util.ArrayList<>(actor.permissions().size() + 1);
        actor.permissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p.name())));
        authorities.add(new SimpleGrantedAuthority("ACTOR_" + actor.type().name()));
        return authorities;
    }

    public Actor actor() {
        return actor;
    }

    @Override
    public Object getCredentials() {
        return credentials;
    }

    @Override
    public Object getPrincipal() {
        return actor;
    }

    @Override
    public String getName() {
        return actor.id().toString();
    }
}
