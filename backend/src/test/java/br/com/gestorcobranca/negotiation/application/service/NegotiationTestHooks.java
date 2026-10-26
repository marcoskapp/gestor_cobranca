package br.com.gestorcobranca.negotiation.application.service;

import org.springframework.context.ApplicationContext;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Acesso dos testes de integração às tarefas diárias do módulo (o agendamento fica desligado nos
 * testes), com o dia de referência simulado. Chame dentro de {@code TenantExecution}.
 */
public final class NegotiationTestHooks {

    private NegotiationTestHooks() {
    }

    public static int expireProposals(ApplicationContext context, LocalDate today) {
        return context.getBean(NegotiationMaintenance.class).expireProposals(today);
    }

    /**
     * Como o job diário (sem a expiração de propostas): confere as suspensões da quebra em vigor e varre os
     * acordos com parcela vencida, no dia simulado.
     */
    public static void daily(ApplicationContext context, LocalDate today) {
        NegotiationMaintenance maintenance = context.getBean(NegotiationMaintenance.class);
        UUID cursor = maintenance.reconcileBreakSuspensions(null, today);
        while (cursor != null) {
            cursor = maintenance.reconcileBreakSuspensions(cursor, today);
        }
        sweepAgreements(context, today);
    }

    /** Varre todos os lotes; devolve o número de lotes. */
    public static int sweepAgreements(ApplicationContext context, LocalDate today) {
        NegotiationMaintenance maintenance = context.getBean(NegotiationMaintenance.class);
        int batches = 1;
        UUID cursor = maintenance.sweepAgreements(null, today);
        while (cursor != null) {
            cursor = maintenance.sweepAgreements(cursor, today);
            batches++;
        }
        return batches;
    }
}
