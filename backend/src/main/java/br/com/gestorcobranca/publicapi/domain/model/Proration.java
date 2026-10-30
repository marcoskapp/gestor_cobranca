package br.com.gestorcobranca.publicapi.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Fator de proporcionalidade de um ciclo de consumo: {@code cycleDays ÷ referenceDays} (decisão 621). Fator 1 no ciclo
 * regular; diferente de 1 só no ciclo de transição.
 * <ul>
 *   <li><b>Mensalidade</b>: {@code mensalidade × cycleDays ÷ referenceDays}, arredondada a centavos com
 *       {@link Money#ROUNDING} (HALF_EVEN, o mesmo do restante do sistema), numa única operação (sem arredondar o
 *       fator antes).</li>
 *   <li><b>Franquias</b>: {@code franquia × cycleDays ÷ referenceDays}, quantidade inteira arredondada para cima (a
 *       favor do credor: nunca menos franquia do que a proporção dos dias).</li>
 * </ul>
 */
public record Proration(int cycleDays, int referenceDays) {

    public static final Proration FULL = new Proration(1, 1);

    public Proration {
        Guard.require(cycleDays >= 1 && referenceDays >= 1, "proration.invalid", "Dias do ciclo inválidos");
    }

    public boolean isFull() {
        return cycleDays == referenceDays;
    }

    /** Valor monetário proporcional, em centavos (HALF_EVEN). */
    public BigDecimal money(BigDecimal full) {
        if (isFull()) {
            return full.setScale(2, Money.ROUNDING);
        }
        return full.multiply(BigDecimal.valueOf(cycleDays))
                .divide(BigDecimal.valueOf(referenceDays), 2, Money.ROUNDING);
    }

    /** Franquia proporcional, inteira, arredondada para cima. */
    public long allowance(long full) {
        if (isFull() || full == 0) {
            return full;
        }
        return BigDecimal.valueOf(full).multiply(BigDecimal.valueOf(cycleDays))
                .divide(BigDecimal.valueOf(referenceDays), 0, RoundingMode.CEILING).longValueExact();
    }
}
