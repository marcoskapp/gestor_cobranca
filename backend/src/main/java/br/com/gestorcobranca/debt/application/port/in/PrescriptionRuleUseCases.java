package br.com.gestorcobranca.debt.application.port.in;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Regras de prescrição do tenant por natureza do título e pretensão (no máximo uma por par).
 * Os prazos não são fixados pelo sistema: a assessoria cadastra os que adota, com o
 * fundamento, ou parte da tabela de mercado ({@link #applyMarketDefaults()}), cujas regras
 * ficam pendentes de validação jurídica até serem confirmadas ({@link #validate}) ou
 * alteradas. Quem cadastra, altera ou confirma assume a validação (auditado).
 * <p>
 * As regras valem para todos os credores da assessoria: alterações exigem escopo sobre todos
 * os credores. Mudanças que afetam o cálculo agendam o recálculo da prescrição dos títulos da
 * natureza afetada.
 */
public interface PrescriptionRuleUseCases {

    List<RuleView> list();

    RuleView get(UUID ruleId);

    /** Par (natureza, pretensão) já com regra → conflito {@code prescription_rule.duplicate}. */
    RuleView create(RuleCommand command);

    /** Natureza e pretensão da regra são imutáveis (os campos do comando são ignorados). */
    RuleView update(UUID ruleId, RuleCommand command);

    /** Confirma regra pendente de validação jurídica sem alterá-la. */
    RuleView validate(UUID ruleId);

    /** Sem regra, os títulos da natureza ficam sem data estimada para a pretensão. */
    void delete(UUID ruleId, String reason);

    /**
     * Copia da tabela de mercado as regras dos pares ainda sem regra (as existentes não são
     * tocadas), como pendentes de validação. Idempotente.
     *
     * @return regras criadas agora
     */
    List<RuleView> applyMarketDefaults();

    /**
     * @param instrumentType     CONTRACT, CCB, PROMISSORY_NOTE, DUPLICATE, CHECK, DEBT_CONFESSION ou OTHER
     * @param claim              EXECUTION (execução do título executivo) ou COLLECTION (cobrança/monitória)
     * @param termMonths         prazo em meses (1 a 360)
     * @param base               TITLE_DUE_DATE ou CONTRACT_FINAL_DUE_DATE
     * @param startOffsetDays    dias somados à data-base antes de iniciar a contagem (ex.: apresentação do cheque)
     * @param interruptionPolicy FIRST_ONLY ou EVERY
     * @param alertDays          antecedência do alerta de prescrição próxima, em dias
     * @param legalBasis         fundamento adotado (texto livre, opcional)
     */
    record RuleCommand(String instrumentType, String claim, int termMonths, String base, int startOffsetDays,
                       String interruptionPolicy, int alertDays, String legalBasis) {
    }

    /** @param reviewStatus PENDING_LEGAL_REVIEW ou VALIDATED */
    record RuleView(UUID id, String instrumentType, String claim, int termMonths, String base, int startOffsetDays,
                    String interruptionPolicy, int alertDays, String legalBasis, String reviewStatus,
                    Instant reviewedAt, UUID reviewedBy, Instant updatedAt, UUID updatedBy) {
    }
}
