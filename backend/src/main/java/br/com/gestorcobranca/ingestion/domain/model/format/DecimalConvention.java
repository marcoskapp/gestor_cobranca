package br.com.gestorcobranca.ingestion.domain.model.format;

/** Separador decimal de uma coluna; o outro sinal ({@code .} ou {@code ,}) e o espaço são separadores de milhar. */
public enum DecimalConvention {
    /** Vírgula decimal (padrão brasileiro): {@code 1.234,56}. */
    COMMA(',', '.'),
    /** Ponto decimal: {@code 1,234.56} ou {@code 1234.56}. */
    DOT('.', ',');

    private final char decimal;
    private final char grouping;

    DecimalConvention(char decimal, char grouping) {
        this.decimal = decimal;
        this.grouping = grouping;
    }

    public char decimal() {
        return decimal;
    }

    public char grouping() {
        return grouping;
    }
}
