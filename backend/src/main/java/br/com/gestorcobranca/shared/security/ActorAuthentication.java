package br.com.gestorcobranca.shared.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.io.Serial;
import java.util.Collection;
import java.util.List;
import java.util.Set;

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

    /** Prefixo das authorities de escopo de credenciais de integração ({@code SCOPE_titles:read}). */
    public static final String SCOPE_PREFIX = "SCOPE_";

    public ActorAuthentication(Actor actor, String credentials) {
        this(actor, credentials, Set.of());
    }

    private ActorAuthentication(Actor actor, String credentials, Set<String> scopes) {
        super(toAuthorities(actor, scopes));
        this.actor = actor;
        this.credentials = credentials;
        setAuthenticated(true);
    }

    public static ActorAuthentication of(Actor actor) {
        return new ActorAuthentication(actor, null);
    }

    /**
     * Credencial de integração (ator {@code INTEGRATION}, API pública): além das permissões, cada escopo concedido ao
     * token vira a authority {@code SCOPE_<escopo>}, exigida pelos endpoints públicos; as permissões continuam sendo
     * verificadas pelos casos de uso (defesa em profundidade).
     */
    public static ActorAuthentication integration(Actor actor, Set<String> scopes) {
        if (actor.type() != Actor.ActorType.INTEGRATION) {
            throw new IllegalArgumentException("Escopos de integração só valem para o ator INTEGRATION");
        }
        return new ActorAuthentication(actor, null, scopes);
    }

    private static Collection<? extends GrantedAuthority> toAuthorities(Actor actor, Set<String> scopes) {
        List<GrantedAuthority> authorities = new java.util.ArrayList<>(actor.permissions().size() + scopes.size() + 1);
        actor.permissions().forEach(p -> authorities.add(new SimpleGrantedAuthority(p.name())));
        scopes.forEach(s -> authorities.add(new SimpleGrantedAuthority(SCOPE_PREFIX + s)));
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
