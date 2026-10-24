package br.com.gestorcobranca.ingestion.domain.service.review;

import br.com.gestorcobranca.ingestion.domain.model.review.AlertFinding;
import br.com.gestorcobranca.ingestion.domain.model.review.AlertType;
import br.com.gestorcobranca.ingestion.domain.model.review.AmountReference;
import br.com.gestorcobranca.ingestion.domain.model.review.LogScaleStats;
import br.com.gestorcobranca.ingestion.domain.model.review.ReferenceKind;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** Referência: 100, 200, 400, 800, 1600 (mediana 400, σ = 1,027660 — ver {@code LogScaleStatsTest}). */
class AmountOutlierPolicyTest {

    private final AmountOutlierPolicy policy = new AmountOutlierPolicy(AlertSettings.defaults());
    private final AmountReference reference = new AmountReference(ReferenceKind.HISTORY_PORTFOLIO,
            LogScaleStats.of(new double[]{100, 200, 400, 800, 1600}));

    private Optional<AlertFinding> evaluate(String amount) {
        return policy.evaluate(7, "valor_principal", new BigDecimal(amount), reference);
    }

    @Test
    void valuesWithinRangeHaveNoAlert() {
        assertThat(evaluate("400.00")).isEmpty();
        assertThat(evaluate("6400.00")).isEmpty();
        assertThat(evaluate("25.00")).isEmpty();
    }

    @Test
    void valueHundredTimesTheTypicalIsProbableDecimalSeparator() {
        // 40.000 (z = 4,48) ÷ 100 = 400 (z = 0): provável "400,00" lido como 40000
        AlertFinding f = evaluate("40000.00").orElseThrow();
        assertThat(f.type()).isEqualTo(AlertType.DECIMAL_SCALE);
        assertThat(f.suggestedExponent()).isEqualTo(-2);
        assertThat(f.rowNumber()).isEqualTo(7);
        assertThat(f.priority()).isEqualTo(AlertType.DECIMAL_SCALE.priority() + 4);
        assertThat(f.reference()).isEqualTo(ReferenceKind.HISTORY_PORTFOLIO);
        assertThat(f.referenceSize()).isEqualTo(5);
        assertThat(f.expectedLow()).isEqualByComparingTo("51.22");
        assertThat(f.expectedHigh()).isEqualByComparingTo("3123.73");
        assertThat(f.detail()).contains("R$ 40.000,00", "Dividido por 100", "R$ 400,00", "separador decimal");
    }

    @Test
    void valueThousandTimesSmallerIsAlsoScale() {
        // 0,40 ×1000 = 400
        AlertFinding f = evaluate("0.40").orElseThrow();
        assertThat(f.type()).isEqualTo(AlertType.DECIMAL_SCALE);
        assertThat(f.suggestedExponent()).isEqualTo(3);
        assertThat(f.detail()).contains("Multiplicado por 1000");
    }

    @Test
    void outlierWithoutPowerOfTenExplanationIsHighOrLow() {
        // referência estreita: mediana 1.000, todos iguais → σ = piso 0,5; 9.000 → z = ln9/0,5 = 4,39; ÷10 = 900 (z = −0,21)
        AmountReference narrow = new AmountReference(ReferenceKind.FILE, LogScaleStats.of(new double[]{1000, 1000, 1000}));
        assertThat(policy.evaluate(1, "valor_principal", new BigDecimal("9000"), narrow).orElseThrow().type())
                .isEqualTo(AlertType.DECIMAL_SCALE);
        // 6.000 → z = ln6/0,5 = 3,58; ÷10 = 600 (z = −1,02) → escala; 5.500 → z = 3,41 → sem alerta
        assertThat(policy.evaluate(1, "valor_principal", new BigDecimal("5500"), narrow)).isEmpty();
        // 50.000 → z = 7,82; ÷10 = 5.000 (3,22), ÷100 = 500 (1,39) → escala com 10⁻²
        assertThat(policy.evaluate(1, "valor_principal", new BigDecimal("50000"), narrow).orElseThrow().suggestedExponent())
                .isEqualTo(-2);
        // distribuição larga: 100 … 1.000.000 (mediana 1.000, MAD = ln 10 → σ = 3,41); 10⁹ → z = 6,9/3,41 = 2,0 → sem alerta
        AmountReference wide = new AmountReference(ReferenceKind.FILE,
                LogScaleStats.of(new double[]{100, 1000, 1000, 1000, 1_000_000}));
        assertThat(policy.evaluate(1, "valor_principal", new BigDecimal("50000000000"), wide).orElseThrow().type())
                .isEqualTo(AlertType.AMOUNT_HIGH);
        assertThat(policy.evaluate(1, "valor_principal", new BigDecimal("0.01"), wide).orElseThrow().type())
                .isEqualTo(AlertType.AMOUNT_LOW);
    }

    @Test
    void noReferenceOrNonPositiveAmountMeansNoAlert() {
        assertThat(policy.evaluate(1, "valor_principal", new BigDecimal("1"), null)).isEmpty();
        assertThat(policy.evaluate(1, "valor_principal", BigDecimal.ZERO, reference)).isEmpty();
    }
}
