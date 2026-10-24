package br.com.gestorcobranca.ingestion.domain.service.format;

import br.com.gestorcobranca.ingestion.domain.model.format.AmountStyle;
import br.com.gestorcobranca.ingestion.domain.model.format.ColumnDecision;
import br.com.gestorcobranca.ingestion.domain.model.format.ColumnFormatAnalysis;
import br.com.gestorcobranca.ingestion.domain.model.format.ColumnFormatStatus;
import br.com.gestorcobranca.ingestion.domain.model.format.DecisionSource;
import br.com.gestorcobranca.ingestion.domain.model.format.FileFormatAnalysis;
import br.com.gestorcobranca.ingestion.domain.model.format.FormatOption;
import br.com.gestorcobranca.ingestion.domain.model.format.NumberFormat;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Inferência da convenção por coluna, a partir de todas as linhas ({@code importacao.md} §2.2). */
class ColumnFormatAnalyzerTest {

    private static ColumnFormatAnalysis analyze(String... values) {
        ColumnFormatAnalyzer analyzer = new ColumnFormatAnalyzer("valor_principal");
        long row = 2;
        for (String v : values) {
            analyzer.accept(row++, v, false);
        }
        return analyzer.result();
    }

    private static BigDecimal total(ColumnFormatAnalysis analysis, FormatOption option) {
        return analysis.option(option).orElseThrow().total();
    }

    @Test
    void brazilianColumnIsDetected() {
        ColumnFormatAnalysis a = analyze("1.234,56", "10,00", "999");
        assertThat(a.status()).isEqualTo(ColumnFormatStatus.DETECTED);
        assertThat(a.recommended()).isEqualTo(FormatOption.COMMA);
        assertThat(a.allowed()).containsExactly(FormatOption.COMMA);
        assertThat(total(a, FormatOption.COMMA)).isEqualByComparingTo("2243.56");
        assertThat(a.autoDecision()).contains(new ColumnDecision(FormatOption.COMMA, DecisionSource.DETECTED));
    }

    @Test
    void dotDecimalColumnIsDetectedAndOneDotZeroZeroStaysOne() {
        ColumnFormatAnalysis a = analyze("1.00", "10.50", "2");
        assertThat(a.status()).isEqualTo(ColumnFormatStatus.DETECTED);
        assertThat(a.recommended()).isEqualTo(FormatOption.DOT);
        assertThat(total(a, FormatOption.DOT)).isEqualByComparingTo("13.50");
    }

    @Test
    void ambiguousValuesAreResolvedByTheColumnEvidence() {
        // "1,5" prova vírgula decimal: "1.000" é mil
        ColumnFormatAnalysis a = analyze("1.000", "1,5", "2.500");
        assertThat(a.status()).isEqualTo(ColumnFormatStatus.INFERRED);
        assertThat(a.recommended()).isEqualTo(FormatOption.COMMA);
        assertThat(total(a, FormatOption.COMMA)).isEqualByComparingTo("3501.50");
        assertThat(a.autoDecision()).contains(new ColumnDecision(FormatOption.COMMA, DecisionSource.INFERRED));
        // "10.5" prova ponto decimal: "1.000" é um real
        ColumnFormatAnalysis b = analyze("1.000", "10.5");
        assertThat(b.status()).isEqualTo(ColumnFormatStatus.INFERRED);
        assertThat(b.recommended()).isEqualTo(FormatOption.DOT);
        assertThat(total(b, FormatOption.DOT)).isEqualByComparingTo("11.50");
    }

    @Test
    void columnWithOnlyAmbiguousValuesRequiresDecisionShowingBothTotals() {
        ColumnFormatAnalysis a = analyze("1.000", "2.500", "300");
        assertThat(a.status()).isEqualTo(ColumnFormatStatus.AMBIGUOUS);
        assertThat(a.requiresDecision()).isTrue();
        assertThat(a.recommended()).isNull();
        assertThat(a.autoDecision()).isEmpty();
        assertThat(a.allowed()).containsExactly(FormatOption.COMMA, FormatOption.DOT);
        assertThat(total(a, FormatOption.COMMA)).isEqualByComparingTo("3800.00");
        assertThat(total(a, FormatOption.DOT)).isEqualByComparingTo("303.50");
        assertThat(a.samples()).first().satisfies(s -> {
            assertThat(s.style()).isEqualTo(AmountStyle.AMBIGUOUS_DOT);
            assertThat(s.raw()).isEqualTo("1.000");
            assertThat(s.rowNumber()).isEqualTo(2);
        });
    }

    @Test
    void mixedConventionsBlockAndOfferPerValueOptions() {
        ColumnFormatAnalysis a = analyze("1.234,56", "1,234.56", "1.000", "10");
        assertThat(a.status()).isEqualTo(ColumnFormatStatus.MIXED);
        assertThat(a.requiresDecision()).isTrue();
        assertThat(a.allowed()).containsExactly(FormatOption.values());
        // vírgula decimal invalida "1,234.56"; ponto decimal invalida "1.234,56"
        assertThat(a.option(FormatOption.COMMA).orElseThrow().invalidCount()).isEqualTo(1);
        assertThat(a.option(FormatOption.DOT).orElseThrow().invalidCount()).isEqualTo(1);
        assertThat(total(a, FormatOption.PER_VALUE_GROUPING)).isEqualByComparingTo("3479.12");
        assertThat(total(a, FormatOption.PER_VALUE_DECIMAL)).isEqualByComparingTo("2480.12");
        assertThat(a.option(FormatOption.PER_VALUE_GROUPING).orElseThrow().invalidCount()).isZero();
    }

    @Test
    void ambiguousDotAndCommaInTheSameColumnAreMixed() {
        assertThat(analyze("1.000", "1,000").status()).isEqualTo(ColumnFormatStatus.MIXED);
    }

    @Test
    void typedAndPlainValuesNeedNoDecision() {
        ColumnFormatAnalyzer analyzer = new ColumnFormatAnalyzer("saldo");
        analyzer.accept(2, "1234.5", true);
        analyzer.accept(3, "100", false);
        analyzer.accept(4, "  ", false);
        ColumnFormatAnalysis a = analyzer.result();
        assertThat(a.status()).isEqualTo(ColumnFormatStatus.NOT_NEEDED);
        assertThat(a.typedCount()).isEqualTo(1);
        assertThat(a.textCount()).isEqualTo(1);
        assertThat(total(a, FormatOption.COMMA)).isEqualByComparingTo("1334.50");
    }

    @Test
    void textNumbersInSpreadsheetAreCountedApartFromTypedOnes() {
        ColumnFormatAnalyzer analyzer = new ColumnFormatAnalyzer("valor_principal");
        analyzer.accept(2, "1000", true);
        analyzer.accept(3, "1.000", false);
        ColumnFormatAnalysis a = analyzer.result();
        assertThat(a.typedCount()).isEqualTo(1);
        assertThat(a.textCount()).isEqualTo(1);
        assertThat(a.status()).isEqualTo(ColumnFormatStatus.AMBIGUOUS);
        assertThat(total(a, FormatOption.DOT)).isEqualByComparingTo("1001.00");
    }

    @Test
    void invalidValuesAreCountedPerOption() {
        ColumnFormatAnalysis a = analyze("1,5", "abc", "1e3");
        assertThat(a.option(FormatOption.COMMA).orElseThrow().invalidCount()).isEqualTo(2);
        assertThat(a.count(AmountStyle.INVALID)).isEqualTo(2);
    }

    @Test
    void resolutionUsesDetectionAndRequiresDecisionForAmbiguousColumns() {
        ColumnFormatAnalyzer principal = new ColumnFormatAnalyzer("valor_principal");
        principal.accept(2, "1.000", false);
        ColumnFormatAnalyzer balance = new ColumnFormatAnalyzer("saldo");
        balance.accept(2, "1.234,56", false);
        FileFormatAnalysis file = new FileFormatAnalysis(1, List.of(principal.result(), balance.result()));
        assertThat(file.requiresDecision()).isTrue();
        assertThat(file.suggestion("valor_principal")).contains(FormatOption.COMMA);

        NumberFormat.Resolution open = NumberFormat.resolve(file, null);
        assertThat(open.undecided()).containsExactly("valor_principal");
        assertThat(open.applied().decision("saldo")).contains(new ColumnDecision(FormatOption.COMMA, DecisionSource.DETECTED));

        NumberFormat chosen = new NumberFormat(Map.of("valor_principal", new ColumnDecision(FormatOption.DOT, DecisionSource.USER)));
        NumberFormat.Resolution done = NumberFormat.resolve(file, chosen);
        assertThat(done.complete()).isTrue();
        assertThat(done.applied().decision("valor_principal")).contains(new ColumnDecision(FormatOption.DOT, DecisionSource.USER));

        // decisão incompatível com os valores (a coluna prova vírgula decimal)
        NumberFormat wrong = new NumberFormat(Map.of("saldo", new ColumnDecision(FormatOption.DOT, DecisionSource.API),
                "valor_principal", new ColumnDecision(FormatOption.COMMA, DecisionSource.API)));
        assertThat(NumberFormat.resolve(file, wrong).conflicting()).containsExactly("saldo");
    }
}
