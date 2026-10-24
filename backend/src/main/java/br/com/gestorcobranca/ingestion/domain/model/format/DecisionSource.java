package br.com.gestorcobranca.ingestion.domain.model.format;

/** Origem da interpretação aplicada a uma coluna. */
public enum DecisionSource {
    /** Coluna sem valores que dependam da convenção. */
    NOT_NEEDED,
    /** Convenção provada pelos próprios valores. */
    DETECTED,
    /** Ambíguos resolvidos pela convenção provada pela coluna. */
    INFERRED,
    /** Escolhida (ou confirmada) pelo usuário na conferência. */
    USER,
    /** Informada pelo sistema integrador ({@code numberFormat}/{@code decimalSeparator}). */
    API
}
