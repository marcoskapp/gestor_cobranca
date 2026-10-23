package br.com.gestorcobranca.legal.domain.model;

/** Quem adiantou a despesa (decisão pendente da issue #14: parâmetro {@code allowedExpenseAdvancers}). */
public enum ExpenseAdvancer {
    CREDITOR,
    AGENCY,
    LAW_FIRM
}
