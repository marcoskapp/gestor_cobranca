package br.com.gestorcobranca.incentive.domain.model.statement;

/** Ajuste manual: aplicado direto até o limite; acima dele aguarda segunda aprovação. */
public enum AdjustmentStatus {
    APPLIED, PENDING_SECOND_APPROVAL, REJECTED
}
