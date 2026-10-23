package br.com.gestorcobranca.debt.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Regras de prescrição do tenant por natureza do título (no máximo uma por natureza). Os prazos
 * não são fixados pelo sistema: a assessoria cadastra os que adota, com o fundamento. Leitura
 * exige {@code POLICY_VIEW}; alterações, {@code POLICY_MANAGE}. Toda alteração é auditada e
 * agenda o recálculo da prescrição dos títulos da natureza afetada.
 */
public interface PrescriptionRuleUseCases {

    List<RuleView> list();

    RuleView get(UUID ruleId);

    /** Natureza já com regra → conflito {@code prescription_rule.duplicate}. */
    RuleView create(RuleCommand command);

    /** A natureza do título da regra é imutável ({@code instrumentType} do comando é ignorado). */
    RuleView update(UUID ruleId, RuleCommand command);

    /** Sem regra, os títulos da natureza ficam sem data estimada. */
    void delete(UUID ruleId);

    /**
     * @param instrumentType     CONTRACT, CCB, PROMISSORY_NOTE, DUPLICATE, CHECK, DEBT_CONFESSION ou OTHER
     * @param base               TITLE_DUE_DATE ou CONTRACT_FINAL_DUE_DATE
     * @param interruptionPolicy FIRST_ONLY ou EVERY
     * @param legalBasis         fundamento adotado (texto livre, opcional)
     */
    record RuleCommand(String instrumentType, int termYears, String base, String interruptionPolicy,
                       String legalBasis) {
    }

    record RuleView(UUID id, String instrumentType, int termYears, String base, String interruptionPolicy,
                    String legalBasis, Instant updatedAt, UUID updatedBy) {
    }
}
