package br.com.gestorcobranca.legal.domain.model;

/** Como o caso chegou ao jurídico. */
public enum MatterOrigin {
    /** Varredura diária pelas regras de elegibilidade. */
    RULE,
    /** Alerta de prescrição próxima do módulo debt avaliado contra as regras. */
    PRESCRIPTION_ALERT,
    /** Indicação manual (com motivo). */
    MANUAL
}
