package br.com.gestorcobranca.legal.application.port.in;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Regras de indicação ao jurídico por credor/carteira e avaliação de um caso de cobrança contra
 * elas (prévia, sem indicar). Regras exigem {@code LEGAL_MANAGE}, escopo do credor e usuário
 * interno. A varredura diária e o alerta de prescrição próxima do debt indicam automaticamente os
 * casos elegíveis.
 */
public interface LegalEligibilityUseCases {

    List<RuleView> listRules();

    RuleView getRule(UUID ruleId);

    RuleView createRule(RuleCommand command);

    /** Credor e carteira são imutáveis. */
    RuleView updateRule(UUID ruleId, RuleCommand command);

    RuleView setRuleActive(UUID ruleId, boolean active);

    /** Avalia o caso de cobrança contra as regras ativas do credor (e a indicação manual). */
    EligibilityPreview evaluate(UUID collectionCaseId);

    record RuleCommand(UUID creditorId, UUID portfolioId, String name, Integer minDaysOverdue, BigDecimal minOpenBalance,
                       boolean requireCollateral, boolean requireExecutiveTitle, Integer minBrokenAgreements,
                       Integer prescriptionWithinDays, boolean requireCadenceExhausted) {
    }

    record RuleView(UUID id, UUID creditorId, UUID portfolioId, String name, boolean active, Integer minDaysOverdue,
                    BigDecimal minOpenBalance, boolean requireCollateral, boolean requireExecutiveTitle,
                    Integer minBrokenAgreements, Integer prescriptionWithinDays, boolean requireCadenceExhausted,
                    long version) {
    }

    /**
     * @param manual      resultado sem regra (o que uma indicação manual levaria)
     * @param rules       resultado por regra ativa aplicável
     * @param openMatterId caso jurídico já aberto para o caso de cobrança, se houver
     */
    record EligibilityPreview(UUID collectionCaseId, UUID openMatterId, RuleOutcome manual, List<RuleOutcome> rules) {
    }

    /**
     * @param unmet     critérios não atendidos (CASE_STATUS, NO_ELIGIBLE_TITLES, ALL_TITLES_PRESCRIBED,
     *                  MIN_DAYS_OVERDUE, MIN_OPEN_BALANCE, COLLATERAL_REQUIRED, EXECUTIVE_TITLE_REQUIRED,
     *                  MIN_BROKEN_AGREEMENTS, PRESCRIPTION_NOT_NEAR)
     * @param flags     sinalizações (PRESCRIBED_TITLES_EXCLUDED, PRESCRIPTION_NEAR…)
     */
    record RuleOutcome(UUID ruleId, String ruleName, boolean eligible, List<String> unmet, Set<UUID> titleIds,
                       Set<UUID> prescribedExcluded, Set<UUID> disputedExcluded, Set<String> flags,
                       BigDecimal openBalance, long maxDaysOverdue) {
    }
}
