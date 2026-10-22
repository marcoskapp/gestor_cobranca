package br.com.gestorcobranca.shared.tenancy;

import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.ActorAuthentication;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Troca o tenant <strong>dentro da transação corrente</strong>. Uso restrito a operações de
 * plataforma que precisam ser atômicas entre tenants — o caso típico é o provisionamento de uma
 * nova assessoria (cria o tenant e, na mesma transação, seus perfis e o administrador).
 * <p>
 * O contexto de persistência é descarregado ({@code flush}) antes de restaurar o tenant
 * original, para que as gravações sejam validadas pelo RLS sob o tenant correto.
 */
@Component
public class TenantSwitch {

    private static final String SET_SQL = "select set_config('app.tenant_id', ?, true), set_config('app.actor_id', ?, true)";

    @PersistenceContext
    private EntityManager entityManager;

    private final JdbcTemplate jdbcTemplate;

    public TenantSwitch(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public <T> T callAsSystemOf(UUID tenantId, String processName, Supplier<T> action) {
        SecurityContext previous = SecurityContextHolder.getContext();
        Actor previousActor = previous.getAuthentication() instanceof ActorAuthentication auth ? auth.actor() : null;
        Actor target = Actor.system(tenantId, processName);

        entityManager.flush();
        apply(target);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(ActorAuthentication.of(target));
        SecurityContextHolder.setContext(context);
        try {
            T result = action.get();
            entityManager.flush();
            return result;
        } finally {
            SecurityContextHolder.setContext(previous);
            apply(previousActor);
        }
    }

    private void apply(Actor actor) {
        jdbcTemplate.queryForList(SET_SQL,
                actor == null ? "" : actor.tenantId().toString(),
                actor == null ? "" : actor.id().toString());
    }
}
