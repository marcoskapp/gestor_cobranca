package br.com.gestorcobranca.collection.application.service;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.UUID;

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

    public static int followUpCreditorRequests(ApplicationContext context) {
        return context.getBean(CollectionMaintenance.class).followUpCreditorRequests();
    }

    public static int reconcileDisputeAdjustments(ApplicationContext context) {
        return context.getBean(DisputeAdjustments.class).sweep(500);
    }

    /** Uma passada do job {@code collection.auto-distribution} na assessoria (abre as próprias transações). */
    public static int autoDistribute(ApplicationContext context, UUID tenantId) {
        return context.getBean(CollectionJobs.class).runAutoDistribution(tenantId);
    }

    /**
     * Fixa o sorteio da vez de desenvolvimento do "próximo caso" (0–99; nulo volta ao aleatório). Exige o bean de
     * {@link DevelopmentRollConfig} no contexto do teste.
     */
    public static void developmentRoll(ApplicationContext context, Integer roll) {
        context.getBean(FixedDevelopmentRoll.class).fix(roll);
    }

    /** Troca o sorteio da vez de desenvolvimento pelo {@link FixedDevelopmentRoll} (importe no teste). */
    @TestConfiguration
    public static class DevelopmentRollConfig {

        @Bean
        @Primary
        FixedDevelopmentRoll fixedDevelopmentRoll() {
            return new FixedDevelopmentRoll();
        }
    }

    /** Sorteio de teste: o valor fixado ou, sem valor, o aleatório da produção. */
    public static final class FixedDevelopmentRoll extends DevelopmentRoll {

        private volatile Integer fixed;

        void fix(Integer roll) {
            this.fixed = roll;
        }

        @Override
        int next() {
            Integer value = fixed;
            return value == null ? super.next() : value;
        }
    }
}
