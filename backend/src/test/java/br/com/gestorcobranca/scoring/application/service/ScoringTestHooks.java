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

    /** Retenção de fatos e snapshots apurados (manutenção {@code scoring.retention}). */
    public static int purge(ApplicationContext context) {
        return context.getBean(OutcomeService.class).purge();
    }

    /**
     * Uma rodada da retenção da inteligência de contato (manutenção {@code scoring.contact_retention}); o teste
     * repete em transações novas enquanto {@link RetentionRound#more()}, como o agendamento.
     */
    public static RetentionRound purgeContactFactsRound(ApplicationContext context) {
        ContactRetentionService.Round round = context.getBean(ContactRetentionService.class).purgeBatch();
        return new RetentionRound(round.removed(), round.more());
    }

    /** @param removed fatos apagados + desfechos sem identificação na rodada */
    public record RetentionRound(int removed, boolean more) {
    }

    /** Base das taxas da inteligência de contato (manutenção {@code scoring.contact_rate_base}). */
    public static int refreshContactRateBase(ApplicationContext context) {
        return context.getBean(ContactRateBaseService.class).refresh();
    }
}
