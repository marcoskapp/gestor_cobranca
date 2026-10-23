package br.com.gestorcobranca.incentive.domain.model.plan;

/** Tratamento de parcelas pagas após o desligamento do negociador (§3.3; validar com RH/jurídico). */
public enum TerminationPolicy {
    /** Creditadas se pagas até a data de desligamento + carência do plano (padrão). */
    CREDIT_UNTIL_TERMINATION_PLUS_GRACE,
    /** Creditadas somente se pagas até a data de desligamento. */
    CREDIT_UNTIL_TERMINATION,
    /** Sempre creditadas. */
    ALWAYS_CREDIT
}
