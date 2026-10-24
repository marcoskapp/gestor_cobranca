package br.com.gestorcobranca.ingestion.domain.model.format;

/**
 * Classe de um valor monetário em texto quanto aos separadores ({@code docs/tecnico/desenho/importacao.md} §2.1).
 * As classes {@code COMMA_DECIMAL} e {@code DOT_DECIMAL} são <b>evidência</b> da convenção da coluna; as ambíguas não
 * provam nada (milhar ou decimal com três casas).
 */
public enum AmountStyle {
    /** Valor tipado (célula numérica do XLSX ou número JSON): sem ambiguidade. */
    TYPED,
    /** Só dígitos ({@code 1234}). */
    PLAIN,
    /** Vírgula decimal / ponto (ou espaço) de milhar: {@code 1.234,56}, {@code 1,5}, {@code 1.000.000}. */
    COMMA_DECIMAL,
    /** Ponto decimal / vírgula (ou espaço) de milhar: {@code 1,234.56}, {@code 10.5}, {@code 1,000,000}. */
    DOT_DECIMAL,
    /** Um ponto seguido de exatamente três dígitos: {@code 1.000}, {@code 1.234}. */
    AMBIGUOUS_DOT,
    /** Uma vírgula seguida de exatamente três dígitos: {@code 1,000}, {@code 12,345}. */
    AMBIGUOUS_COMMA,
    /** Não é um valor monetário aceito (separadores inválidos, símbolos, notação científica). */
    INVALID;

    public boolean isAmbiguous() {
        return this == AMBIGUOUS_DOT || this == AMBIGUOUS_COMMA;
    }
}
