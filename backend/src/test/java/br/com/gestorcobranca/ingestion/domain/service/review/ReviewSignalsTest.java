package br.com.gestorcobranca.ingestion.domain.service.review;

import br.com.gestorcobranca.ingestion.domain.model.review.AlertFinding;
import br.com.gestorcobranca.ingestion.domain.model.review.AlertType;
import br.com.gestorcobranca.ingestion.domain.model.review.LogScaleStats;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewSignalsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 24);
    private final ReviewSignals signals = new ReviewSignals(AlertSettings.defaults());

    @Test
    void balanceAboveWhatChargesJustify() {
        // 6 meses de atraso: teto = 1.000 × (1 + 0,02 + 0,10 × 6) = 1.620,00
        LocalDate due = TODAY.minusMonths(6);
        assertThat(signals.balance(2, new BigDecimal("1000"), new BigDecimal("1620.00"), due, TODAY)).isEmpty();
        AlertFinding f = signals.balance(2, new BigDecimal("1000"), new BigDecimal("1700.00"), due, TODAY).orElseThrow();
        assertThat(f.type()).isEqualTo(AlertType.BALANCE_ABOVE_PRINCIPAL);
        assertThat(f.expectedHigh()).isEqualByComparingTo("1620.00");
        assertThat(f.suggestedExponent()).isNull();
        // saldo 100× o principal: destaque de separador
        AlertFinding scale = signals.balance(2, new BigDecimal("10.00"), new BigDecimal("1000.00"), TODAY.plusDays(5), TODAY)
                .orElseThrow();
        assertThat(scale.suggestedExponent()).isEqualTo(-2);
        assertThat(scale.priority()).isGreaterThan(f.priority());
        assertThat(scale.detail()).contains("separador decimal");
        // saldo menor que o principal (pagamento parcial) não é alerta
        assertThat(signals.balance(2, new BigDecimal("1000"), new BigDecimal("10"), due, TODAY)).isEmpty();
    }

    @Test
    void dueDatesTooFarOrTooOld() {
        assertThat(signals.dueDate(3, TODAY.plusYears(11), TODAY).orElseThrow().type()).isEqualTo(AlertType.DUE_DATE_TOO_FUTURE);
        assertThat(signals.dueDate(3, TODAY.minusYears(21), TODAY).orElseThrow().type()).isEqualTo(AlertType.DUE_DATE_TOO_OLD);
        assertThat(signals.dueDate(3, TODAY.plusYears(5), TODAY)).isEmpty();
        assertThat(signals.dueDate(3, TODAY.minusYears(5), TODAY)).isEmpty();
    }

    @Test
    void debtorWithTitlesOfVeryDifferentMagnitudes() {
        AlertFinding f = signals.debtorSpread(4, new BigDecimal("15000.00"), new BigDecimal("150.00")).orElseThrow();
        assertThat(f.type()).isEqualTo(AlertType.DEBTOR_MAGNITUDE_SPREAD);
        assertThat(f.suggestedExponent()).isEqualTo(-2);
        assertThat(signals.debtorSpread(4, new BigDecimal("1500.00"), new BigDecimal("150.00"))).isEmpty();
        assertThat(signals.debtorSpread(4, new BigDecimal("1.00"), new BigDecimal("150.00"))).isPresent();
    }

    @Test
    void installmentsAgainstContractAmount() {
        // soma 12 × 100 = 1.200 para contrato de 1.000: normal (juros)
        assertThat(signals.contract(2, "C-1", 12, new BigDecimal("1200"), new BigDecimal("1000"))).isEmpty();
        // soma 3.000 > 2 × 1.000
        assertThat(signals.contract(2, "C-1", 3, new BigDecimal("3000"), new BigDecimal("1000"))).isPresent();
        // soma 1.000 para contrato de 100.000 (razão 1/100): separador no valor do contrato
        AlertFinding f = signals.contract(2, "C-1", 2, new BigDecimal("1000"), new BigDecimal("100000")).orElseThrow();
        assertThat(f.suggestedExponent()).isEqualTo(-2);
        assertThat(f.detail()).contains("C-1", "separador");
        // uma parcela só: não se aplica
        assertThat(signals.contract(2, "C-1", 1, new BigDecimal("3000"), new BigDecimal("1000"))).isEmpty();
    }

    @Test
    void centsAlwaysZeroWhenHistoryHasCents() {
        AlertFinding f = signals.cents(1.0, 500, 0.2, 10_000).orElseThrow();
        assertThat(f.type()).isEqualTo(AlertType.CENTS_PATTERN);
        assertThat(f.rowNumber()).isZero();
        assertThat(signals.cents(0.3, 500, 0.2, 10_000)).isEmpty();
        assertThat(signals.cents(0.1, 500, 1.0, 10_000)).isPresent();
        // amostra pequena: sem sinal
        assertThat(signals.cents(1.0, 10, 0.2, 10_000)).isEmpty();
    }

    @Test
    void wholeFileShiftedByPowerOfTen() {
        double[] history = new double[40];
        double[] file = new double[40];
        for (int i = 0; i < 40; i++) {
            history[i] = 100 + i;
            file[i] = (100 + i) * 100;
        }
        AlertFinding f = signals.fileShift(LogScaleStats.of(file), LogScaleStats.of(history)).orElseThrow();
        assertThat(f.type()).isEqualTo(AlertType.FILE_SCALE_SHIFT);
        assertThat(f.suggestedExponent()).isEqualTo(-2);
        assertThat(signals.fileShift(LogScaleStats.of(history), LogScaleStats.of(history))).isEmpty();
    }
}
