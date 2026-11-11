package br.com.gestorcobranca.compliance.domain.model;

/** Unidade do prazo de retenção de uma categoria (fixa por categoria). */
public enum RetentionUnit {
    /** Dias corridos. */
    DAYS,
    /** Meses de calendário, no fuso de operação. */
    MONTHS
}
