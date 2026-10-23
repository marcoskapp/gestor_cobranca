package br.com.gestorcobranca.negotiation.domain.model.simulation;

/**
 * Motivo registrado para subir de nível na escada de concessões (§3). Os códigos são contrato
 * com painéis (em que nível e por quê os acordos fecham); o texto livre complementar fica só no
 * banco, nunca em log.
 */
public enum ConcessionReason {
    /** Capacidade de pagamento declarada abaixo da oferta corrente. */
    PAYMENT_CAPACITY,
    /** Desemprego ou perda de renda. */
    INCOME_LOSS,
    /** Doença ou evento familiar. */
    HEALTH_OR_FAMILY,
    /** Oferta concorrente (outra dívida, feirão, outra assessoria). */
    COMPETING_OFFER,
    /** Atraso muito longo / baixa propensão de recuperação. */
    LOW_RECOVERY_PROSPECT,
    /** Pagamento à vista imediato em troca de concessão. */
    IMMEDIATE_PAYMENT,
    /** Outro motivo (exige observação). */
    OTHER
}
