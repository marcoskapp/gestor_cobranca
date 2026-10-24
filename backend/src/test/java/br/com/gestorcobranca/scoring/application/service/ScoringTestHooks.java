package br.com.gestorcobranca.scoring.application.service;

import org.springframework.context.ApplicationContext;

/**
 * Acesso dos testes de integração às tarefas periódicas do módulo (agendamento desligado nos
 * testes). Chame dentro de {@code TenantExecution} (transação no tenant).
 */
public final class ScoringTestHooks {

    private ScoringTestHooks() {
    }

    public static int resolveOutcomes(ApplicationContext context) {
        return context.getBean(OutcomeService.class).resolveOutcomes();
    }

    public static int measureCohorts(ApplicationContext context) {
        return context.getBean(OutcomeService.class).measureCohorts();
    }
}
