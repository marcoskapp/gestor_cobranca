package br.com.gestorcobranca.portal.application.service;

import org.springframework.context.ApplicationContext;

import java.util.UUID;

/**
 * Acesso dos testes de integração às tarefas periódicas do portal (o agendamento fica desligado nos testes). Os
 * métodos abrem a própria transação no tenant, como o job.
 */
public final class PortalTestHooks {

    private PortalTestHooks() {
    }

    /** Retenção dos dados de acesso de uma assessoria (job {@code portal.retention}). */
    public static int purgeAccessData(ApplicationContext context, UUID tenantId) {
        return context.getBean(PortalJobs.class).purgeAccessData(tenantId);
    }
}
