package br.com.gestorcobranca.ingestion.domain.model.format;

import java.util.Locale;
import java.util.Optional;

/**
 * Interpretação dos valores em texto de uma coluna monetária.
 * <ul>
 *   <li>{@link #COMMA} / {@link #DOT}: convenção única para a coluna (valores incompatíveis viram erro da linha).</li>
 *   <li>{@link #PER_VALUE_GROUPING} / {@link #PER_VALUE_DECIMAL}: só para colunas com convenções misturadas — cada
 *       valor inequívoco pelo próprio formato; os ambíguos ({@code 1.000}, {@code 1,000}) como milhar ou como decimal.</li>
 * </ul>
 */
public enum FormatOption {
    COMMA,
    DOT,
    PER_VALUE_GROUPING,
    PER_VALUE_DECIMAL;

    /** Convenção usada para ler um valor da classe informada sob esta opção. */
    public DecimalConvention conventionFor(AmountStyle style) {
        return switch (this) {
            case COMMA -> DecimalConvention.COMMA;
            case DOT -> DecimalConvention.DOT;
            case PER_VALUE_GROUPING, PER_VALUE_DECIMAL -> switch (style) {
                case DOT_DECIMAL -> DecimalConvention.DOT;
                // ponto ambíguo como milhar = vírgula decimal; como decimal = ponto decimal
                case AMBIGUOUS_DOT -> this == PER_VALUE_GROUPING ? DecimalConvention.COMMA : DecimalConvention.DOT;
                case AMBIGUOUS_COMMA -> this == PER_VALUE_GROUPING ? DecimalConvention.DOT : DecimalConvention.COMMA;
                default -> DecimalConvention.COMMA;
            };
        };
    }

    public boolean perValue() {
        return this == PER_VALUE_GROUPING || this == PER_VALUE_DECIMAL;
    }

    /** Aceita também os nomes curtos usados na API ({@code VIRGULA}, {@code PONTO}). */
    public static Optional<FormatOption> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return switch (raw.strip().toUpperCase(Locale.ROOT)) {
            case "COMMA", "VIRGULA", "VÍRGULA", "," -> Optional.of(COMMA);
            case "DOT", "PONTO", "." -> Optional.of(DOT);
            case "PER_VALUE_GROUPING" -> Optional.of(PER_VALUE_GROUPING);
            case "PER_VALUE_DECIMAL" -> Optional.of(PER_VALUE_DECIMAL);
            default -> Optional.empty();
        };
    }
}
