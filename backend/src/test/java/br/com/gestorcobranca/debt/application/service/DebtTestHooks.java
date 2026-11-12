package br.com.gestorcobranca.debt.application.service;

import org.springframework.context.ApplicationContext;

import java.util.UUID;

/**
 * Acesso dos testes de integração às tarefas periódicas do debt (o agendamento fica desligado nos testes). Os métodos
 * abrem a própria transação no tenant, como o job.
 */
public final class DebtTestHooks {

    private DebtTestHooks() {
    }

    /** Descarte da proposta de aumento do limite vencida de uma assessoria (job diário). */
    public static boolean expireThresholdProposal(ApplicationContext context, UUID tenantId) {
        return context.getBean(DebtJobs.class).expireThresholdProposal(tenantId);
    }

    /** Varredura da novidade dos contatos recebidos na carga de uma assessoria (job diário). */
    public static int sweepContactNovelty(ApplicationContext context, UUID tenantId) {
        return context.getBean(DebtJobs.class).sweepContactNovelty(tenantId);
    }
}
