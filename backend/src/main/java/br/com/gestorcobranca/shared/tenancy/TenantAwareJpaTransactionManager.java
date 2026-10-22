package br.com.gestorcobranca.shared.tenancy;

import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.ActorAuthentication;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.Session;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.Serial;
import java.sql.PreparedStatement;

/**
 * Gerenciador de transações que propaga o tenant e o ator corrente para a sessão PostgreSQL no
 * início de cada transação, por meio de {@code set_config(..., is_local => true)}.
 * <p>
 * As políticas de Row Level Security usam {@code app.tenant_id}: sem tenant definido, nenhuma
 * linha de tabela tenant-scoped fica visível nem pode ser gravada. Como o valor é local à
 * transação, conexões devolvidas ao pool não carregam o tenant anterior.
 * <p>
 * {@code app.actor_id} é usado pelos gatilhos de auditoria do banco como autor da alteração.
 */
public class TenantAwareJpaTransactionManager extends JpaTransactionManager {

    @Serial
    private static final long serialVersionUID = 1L;

    private static final String SET_CONTEXT_SQL =
            "select set_config('app.tenant_id', ?, true), set_config('app.actor_id', ?, true)";

    public TenantAwareJpaTransactionManager(EntityManagerFactory emf) {
        super(emf);
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        super.doBegin(transaction, definition);
        EntityManagerHolder holder = (EntityManagerHolder) TransactionSynchronizationManager.getResource(obtainEntityManagerFactory());
        if (holder == null) {
            throw new IllegalStateException("EntityManager não vinculado à transação");
        }
        Actor actor = currentActor();
        String tenantId = actor == null ? "" : actor.tenantId().toString();
        String actorId = actor == null ? "" : actor.id().toString();
        holder.getEntityManager().unwrap(Session.class).doWork(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(SET_CONTEXT_SQL)) {
                statement.setString(1, tenantId);
                statement.setString(2, actorId);
                statement.execute();
            }
        });
    }

    private static Actor currentActor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof ActorAuthentication actorAuthentication) {
            return actorAuthentication.actor();
        }
        return null;
    }
}
