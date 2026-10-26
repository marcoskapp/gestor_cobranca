package br.com.gestorcobranca.shared.tenancy;

import br.com.gestorcobranca.shared.logging.MdcKeys;
import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.ActorAuthentication;
import org.slf4j.MDC;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Executa código fora de uma requisição HTTP (jobs, consumidores de eventos, webhooks) no
 * contexto de um tenant, com um ator de sistema e em transação própria.
 * <p>
 * Exemplo:
 * <pre>{@code
 * tenantExecution.inTenant(event.tenantId(), "collection.eligibility", () -> service.recalculate(caseId));
 * }</pre>
 * O contexto anterior é sempre restaurado, inclusive em caso de exceção.
 */
@Component
public class TenantExecution {

    private final TransactionTemplate requiresNew;
    private final TransactionTemplate requiresNewReadOnly;

    public TenantExecution(PlatformTransactionManager transactionManager) {
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.requiresNewReadOnly = new TransactionTemplate(transactionManager);
        this.requiresNewReadOnly.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.requiresNewReadOnly.setReadOnly(true);
    }

    /** Executa como ator de sistema do tenant, em nova transação. */
    public void inTenant(UUID tenantId, String processName, Runnable action) {
        callInTenant(tenantId, processName, () -> {
            action.run();
            return null;
        });
    }

    public <T> T callInTenant(UUID tenantId, String processName, Supplier<T> action) {
        return callAs(Actor.system(tenantId, processName), action);
    }

    /** Executa como um ator específico (ex.: devedor autenticado no portal), em nova transação. */
    public <T> T callAs(Actor actor, Supplier<T> action) {
        return callAs(actor, requiresNew, action);
    }

    /**
     * Executa como um ator específico em nova transação <strong>somente leitura</strong> — consultas
     * feitas em outra thread em nome do usuário da requisição (ex.: fontes de pendências).
     */
    public <T> T callAsReadOnly(Actor actor, Supplier<T> action) {
        return callAs(actor, requiresNewReadOnly, action);
    }

    private <T> T callAs(Actor actor, TransactionTemplate template, Supplier<T> action) {
        SecurityContext previous = SecurityContextHolder.getContext();
        String previousTenant = MDC.get(MdcKeys.TENANT_ID);
        String previousActor = MDC.get(MdcKeys.ACTOR_ID);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(ActorAuthentication.of(actor));
        SecurityContextHolder.setContext(context);
        MDC.put(MdcKeys.TENANT_ID, actor.tenantId().toString());
        MDC.put(MdcKeys.ACTOR_ID, actor.id().toString());
        try {
            return template.execute(status -> action.get());
        } finally {
            SecurityContextHolder.setContext(previous);
            restoreMdc(MdcKeys.TENANT_ID, previousTenant);
            restoreMdc(MdcKeys.ACTOR_ID, previousActor);
        }
    }

    /**
     * Executa como ator de sistema do tenant, <strong>sem</strong> abrir transação. Use quando o
     * código chamado gerencia as próprias transações (ex.: um caso de uso {@code @Transactional}
     * que precisa ser repetido por item com isolamento de falha).
     */
    public void withTenantContext(UUID tenantId, String processName, Runnable action) {
        SecurityContext previous = SecurityContextHolder.getContext();
        String previousTenant = MDC.get(MdcKeys.TENANT_ID);
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(ActorAuthentication.of(Actor.system(tenantId, processName)));
        SecurityContextHolder.setContext(context);
        MDC.put(MdcKeys.TENANT_ID, tenantId.toString());
        try {
            action.run();
        } finally {
            SecurityContextHolder.setContext(previous);
            restoreMdc(MdcKeys.TENANT_ID, previousTenant);
        }
    }

    private static void restoreMdc(String key, String value) {
        if (value == null) {
            MDC.remove(key);
        } else {
            MDC.put(key, value);
        }
    }
}
