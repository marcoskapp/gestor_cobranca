package br.com.gestorcobranca.legal.application.service;

import org.springframework.context.ApplicationContext;

import java.util.UUID;

/**
 * Acesso dos testes de integração às varreduras do módulo (o agendamento fica desligado nos
 * testes). {@link #sweepEligibility} e {@link #runDaily} gerenciam as próprias transações; os
 * demais exigem transação no tenant (chame dentro de {@code TenantExecution}).
 */
public final class LegalTestHooks {

    private LegalTestHooks() {
    }

    public static int sweepEligibility(ApplicationContext context, UUID tenantId) {
        return context.getBean(LegalJobs.class).sweepEligibility(tenantId);
    }

    public static void runDaily(ApplicationContext context, UUID tenantId) {
        context.getBean(LegalJobs.class).runDaily(tenantId);
    }

    public static UUID scanDeadlines(ApplicationContext context) {
        return context.getBean(DeadlineMonitor.class).scanBatch(new UUID(0L, 0L), 200);
    }
}
