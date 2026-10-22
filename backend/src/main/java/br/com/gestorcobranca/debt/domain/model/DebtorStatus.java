package br.com.gestorcobranca.debt.domain.model;

/** Ciclo de vida do cadastro do devedor. */
public enum DebtorStatus {
    ACTIVE,
    /** Dados pessoais eliminados a pedido do titular ou por retenção (LGPD); o histórico financeiro permanece. */
    ANONYMIZED
}
