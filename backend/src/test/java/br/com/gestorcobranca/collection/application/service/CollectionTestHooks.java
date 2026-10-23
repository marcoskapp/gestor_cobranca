package br.com.gestorcobranca.collection.application.service;

import org.springframework.context.ApplicationContext;

/**
 * Acesso dos testes de integração às tarefas periódicas do módulo (o agendamento fica desligado
 * nos testes). Os métodos exigem transação no tenant: chame dentro de {@code TenantExecution}.
 */
public final class CollectionTestHooks {

    private CollectionTestHooks() {
    }

    public static int markMissedCallbacks(ApplicationContext context) {
        return context.getBean(CollectionMaintenance.class).markMissedCallbacks();
    }

    public static int breakPromises(ApplicationContext context) {
        return context.getBean(CollectionMaintenance.class).breakPromises();
    }

    public static int flagOverdueDisputes(ApplicationContext context) {
        return context.getBean(CollectionMaintenance.class).flagOverdueDisputes();
    }
}
