package br.com.gestorcobranca.legal.domain.model;

/** Situação da despesa: lançada, reembolsada a quem adiantou ou cancelada (estorno do lançamento). */
public enum ExpenseStatus {
    RECORDED,
    REIMBURSED,
    CANCELLED
}
