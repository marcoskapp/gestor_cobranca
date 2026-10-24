package br.com.gestorcobranca.ingestion.domain.service.format;

import br.com.gestorcobranca.ingestion.domain.model.format.AmountProblem;
import br.com.gestorcobranca.ingestion.domain.model.format.AmountReading;
import br.com.gestorcobranca.ingestion.domain.model.format.AmountStyle;
import br.com.gestorcobranca.ingestion.domain.model.format.FormatOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tabela de casos do leitor de valores ({@code importacao.md} §2.1). Cada linha: texto, classe e o valor lido com
 * vírgula decimal (COMMA) e com ponto decimal (DOT); {@code X} = a interpretação não lê o valor.
 */
class AmountParserTest {

    @ParameterizedTest(name = "{0} → {1}; COMMA {2}; DOT {3}")
    @CsvSource(delimiter = '|', value = {
            // o caso do pedido: 1.00 é um real com ponto decimal e nunca vira 100
            "1.00            | DOT_DECIMAL     | X           | 1.00",
            "1,00            | COMMA_DECIMAL   | 1.00        | X",
            // ambíguos: milhar ou decimal com três casas
            "1.000           | AMBIGUOUS_DOT   | 1000.00     | 1.00",
            "1,000           | AMBIGUOUS_COMMA | 1.00        | 1000.00",
            "1.234           | AMBIGUOUS_DOT   | 1234.00     | X",
            "12,345          | AMBIGUOUS_COMMA | X           | 12345.00",
            "123.456         | AMBIGUOUS_DOT   | 123456.00   | X",
            // inequívocos
            "1.000,00        | COMMA_DECIMAL   | 1000.00     | X",
            "1,000.00        | DOT_DECIMAL     | X           | 1000.00",
            "1.000.000       | COMMA_DECIMAL   | 1000000.00  | X",
            "1,000,000       | DOT_DECIMAL     | X           | 1000000.00",
            "1.234.567,89    | COMMA_DECIMAL   | 1234567.89  | X",
            "1,234,567.89    | DOT_DECIMAL     | X           | 1234567.89",
            "1 000,00        | COMMA_DECIMAL   | 1000.00     | X",
            "1 000.50        | DOT_DECIMAL     | X           | 1000.50",
            "12 345 678,9    | COMMA_DECIMAL   | 12345678.90 | X",
            "R$ 1.234,56     | COMMA_DECIMAL   | 1234.56     | X",
            "R$1234,56       | COMMA_DECIMAL   | 1234.56     | X",
            "1234,56 R$      | COMMA_DECIMAL   | 1234.56     | X",
            "1,5             | COMMA_DECIMAL   | 1.50        | X",
            "10.5            | DOT_DECIMAL     | X           | 10.50",
            "1234.56         | DOT_DECIMAL     | X           | 1234.56",
            "1234,5          | COMMA_DECIMAL   | 1234.50     | X",
            "0.500           | DOT_DECIMAL     | X           | 0.50",
            ",50             | COMMA_DECIMAL   | 0.50        | X",
            "1234            | PLAIN           | 1234.00     | 1234.00",
            "007             | PLAIN           | 7.00        | 7.00",
            // sinais
            "-1,5            | COMMA_DECIMAL   | -1.50       | X",
            "+1,5            | COMMA_DECIMAL   | 1.50        | X",
            "(1,50)          | COMMA_DECIMAL   | -1.50       | X",
            "1.234,56-       | COMMA_DECIMAL   | -1234.56    | X",
            "R$ -10,00       | COMMA_DECIMAL   | -10.00      | X",
            "-R$ 10,00       | COMMA_DECIMAL   | -10.00      | X",
            "(1.000)         | AMBIGUOUS_DOT   | -1000.00    | -1.00",
    })
    void classifiesAndConvertsUnderEachConvention(String raw, AmountStyle style, String comma, String dot) {
        assertThat(AmountParser.lex(raw).style()).isEqualTo(style);
        check(AmountParser.read(raw, FormatOption.COMMA), comma);
        check(AmountParser.read(raw, FormatOption.DOT), dot);
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource(delimiter = '|', value = {
            "1e3             | SCIENTIFIC_NOTATION",
            "1E+3            | SCIENTIFIC_NOTATION",
            "1,23457E+11     | SCIENTIFIC_NOTATION",
            "1.5e-2          | SCIENTIFIC_NOTATION",
            "1..5            | FORMAT",
            "1,,5            | FORMAT",
            "1.,5            | FORMAT",
            "1.              | FORMAT",
            "1,              | FORMAT",
            "12.34.567       | FORMAT",
            "1.2.3,4         | FORMAT",
            "1,234,56.7      | FORMAT",
            "1,234.567,89    | FORMAT",
            "US$ 10,00       | FORMAT",
            "$10.00          | FORMAT",
            "10,00 €         | FORMAT",
            "10%             | FORMAT",
            "abc             | FORMAT",
            "1 0,00          | FORMAT",
            "1 000.000,00    | FORMAT",
            "--1             | FORMAT",
            "-(1,00)         | FORMAT",
            "R$              | FORMAT",
    })
    void rejectsInvalidValues(String raw, AmountProblem problem) {
        assertThat(AmountParser.lex(raw).style()).isEqualTo(AmountStyle.INVALID);
        for (FormatOption option : FormatOption.values()) {
            AmountReading reading = AmountParser.read(raw, option);
            assertThat(reading.ok()).as("%s sob %s", raw, option).isFalse();
            assertThat(reading.problem()).isEqualTo(problem);
        }
    }

    @ParameterizedTest(name = "{0} sob {1}")
    @CsvSource(delimiter = '|', value = {
            "1,234        | COMMA",
            "1.2345       | DOT",
            "1234,567     | COMMA",
            "10.005       | DOT",
            "0,001        | COMMA"})
    void rejectsMoreThanTwoNonZeroDecimals(String raw, FormatOption option) {
        assertThat(AmountParser.read(raw, option).problem()).isEqualTo(AmountProblem.TOO_MANY_DECIMALS);
    }

    @Test
    void acceptsExtraDecimalsWhenTheyAreZeros() {
        check(AmountParser.read("1,500", FormatOption.COMMA), "1.50");
        check(AmountParser.read("25.5000", FormatOption.DOT), "25.50");
    }

    @Test
    void perValueOptionsReadUnambiguousValuesByTheirOwnFormat() {
        // coluna misturada: cada valor inequívoco pelo próprio formato
        for (FormatOption option : new FormatOption[]{FormatOption.PER_VALUE_GROUPING, FormatOption.PER_VALUE_DECIMAL}) {
            check(AmountParser.read("1.234,56", option), "1234.56");
            check(AmountParser.read("1,234.56", option), "1234.56");
            check(AmountParser.read("10.5", option), "10.50");
            check(AmountParser.read("1,5", option), "1.50");
            check(AmountParser.read("1234", option), "1234.00");
        }
        // ambíguos conforme a escolha
        check(AmountParser.read("1.000", FormatOption.PER_VALUE_GROUPING), "1000.00");
        check(AmountParser.read("1,000", FormatOption.PER_VALUE_GROUPING), "1000.00");
        check(AmountParser.read("1.000", FormatOption.PER_VALUE_DECIMAL), "1.00");
        check(AmountParser.read("1,000", FormatOption.PER_VALUE_DECIMAL), "1.00");
        assertThat(AmountParser.read("1.234", FormatOption.PER_VALUE_DECIMAL).problem())
                .isEqualTo(AmountProblem.TOO_MANY_DECIMALS);
    }

    @Test
    void typedValuesHaveNoAmbiguity() {
        check(AmountParser.readTyped("1234.56"), "1234.56");
        check(AmountParser.readTyped("1"), "1.00");
        check(AmountParser.readTyped("-0.5"), "-0.50");
        // número tipado com três casas: antes era lido como milhar (10.005 → 10005)
        assertThat(AmountParser.readTyped("10.005").problem()).isEqualTo(AmountProblem.TOO_MANY_DECIMALS);
        check(AmountParser.readTyped("10.500"), "10.50");
        assertThat(AmountParser.readTyped("1E+5").problem()).isEqualTo(AmountProblem.FORMAT);
        assertThat(AmountParser.read("1.000", true, FormatOption.COMMA).value()).isEqualByComparingTo("1");
        assertThat(AmountParser.read("1.000", false, FormatOption.COMMA).value()).isEqualByComparingTo("1000");
    }

    @Test
    void protectsAgainstPrecisionLossAndHugeValues() {
        check(AmountParser.read("12345678901234567", FormatOption.COMMA), "12345678901234567.00");
        assertThat(AmountParser.read("123456789012345678", FormatOption.COMMA).problem()).isEqualTo(AmountProblem.TOO_LARGE);
        check(AmountParser.read("000000000000000000001", FormatOption.COMMA), "1.00");
    }

    private static void check(AmountReading reading, String expected) {
        if ("X".equals(expected)) {
            assertThat(reading.ok()).as("esperava valor recusado, leu %s", reading.value()).isFalse();
        } else {
            assertThat(reading.ok()).as("esperava %s, problema %s", expected, reading.problem()).isTrue();
            assertThat(reading.value()).isEqualByComparingTo(expected);
            assertThat(reading.value().scale()).isEqualTo(2);
        }
    }
}
