package br.com.gestorcobranca.debt.domain.model;

/** Situação da validação jurídica de uma regra de prescrição. */
public enum RuleReviewStatus {
    /** Regra semeada a partir da tabela de mercado: aplicada, mas ainda não validada pelo jurídico da assessoria. */
    PENDING_LEGAL_REVIEW,
    /** Regra cadastrada, alterada ou confirmada por usuário responsável (auditado). */
    VALIDATED
}
