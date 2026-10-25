package br.com.gestorcobranca.incentive.application.port.in;

import br.com.gestorcobranca.incentive.domain.model.plan.PlanRules;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;

/** Planos de comissão versionados, metas por período e simulação (requer {@code COMMISSION_PLAN_MANAGE}). */
public interface CommissionPlanUseCases {

    PlanView createDraft(PlanCommand command);

    PlanView updateDraft(UUID planId, PlanCommand command);

    /** Descarta (remove) um rascunho; versões publicadas ou aposentadas não podem ser removidas. */
    void discardDraft(UUID planId);

    /** Nova versão em rascunho a partir da versão informada (copia regras); um rascunho por código. */
    PlanView newVersion(UUID planId);

    /** Publica a partir do dia 1 de um mês (mês corrente ou futuro, posterior a período fechado). */
    PlanView publish(UUID planId, LocalDate effectiveFrom);

    /** Encerra a vigência no último dia de um mês. */
    PlanView retire(UUID planId, LocalDate effectiveTo);

    PlanView get(UUID planId);

    List<PlanView> list();

    /**
     * Simula a versão (inclusive rascunho) sobre um período passado: recalcula pesos de crédito e
     * comissão de todos os negociadores com os lançamentos liberados do período, e compara com os
     * extratos efetivos. Não grava nada.
     */
    SimulationView simulate(UUID planId, YearMonth period);

    GoalView setGoal(GoalCommand command);

    List<GoalView> goals(YearMonth period);

    /** Remove a meta de um período ainda aberto (passa a valer a meta padrão do plano, se houver). */
    void removeGoal(UUID goalId);

    /**
     * @param code     identificador estável do plano (ex.: PADRAO_MERCADO); ignorado em updateDraft
     * @param priority desempate quando mais de um plano cobre o mesmo negociador (maior vence)
     */
    record PlanCommand(String code, String name, String description, int priority, PlanRules rules) {
    }

    record PlanView(UUID id, String code, int version, String name, String description, String status, int priority,
                    LocalDate effectiveFrom, LocalDate effectiveTo, PlanRules rules, int rulesSchemaVersion,
                    Instant publishedAt, UUID publishedBy) {
    }

    /** @param subjectType USER ou TEAM */
    record GoalCommand(YearMonth period, String subjectType, UUID subjectId, BigDecimal target) {
    }

    record GoalView(UUID id, YearMonth period, String subjectType, UUID subjectId, BigDecimal target) {
    }

    record SimulationView(UUID planId, String planCode, int planVersion, YearMonth period, List<SimulatedStatement> statements,
                          BigDecimal simulatedTotal, BigDecimal actualTotal, BigDecimal creditedRecovery,
                          BigDecimal simulatedCostPerRecovered, BigDecimal actualCostPerRecovered) {
    }

    record SimulatedStatement(UUID userId, String outcome, BigDecimal creditedRecovery, BigDecimal simulatedAmount,
                              BigDecimal actualAmount) {
    }
}
