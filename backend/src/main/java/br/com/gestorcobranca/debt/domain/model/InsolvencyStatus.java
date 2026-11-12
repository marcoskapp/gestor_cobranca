package br.com.gestorcobranca.debt.domain.model;

/** Situação do processo de insolvência registrado para o devedor. */
public enum InsolvencyStatus {
    /** Em curso: os casos do devedor ficam inelegíveis ({@code DEBTOR_INSOLVENCY}). */
    ACTIVE,
    /** Encerrado (plano cumprido, processo extinto, registro por engano): a elegibilidade volta a ser avaliada. */
    ENDED
}
