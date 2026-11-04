package br.com.gestorcobranca.publicapi.domain.service;

import br.com.gestorcobranca.shared.domain.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Regras de cálculo comuns às duas camadas de cobrança do consumo da API pública — a da assessoria ao credor
 * ({@link PriceCalculator}) e a da plataforma à assessoria ({@code PlatformPriceCalculator}): excedente sobre a
 * franquia, valor de uma quantidade a preço unitário e uso da franquia. Função pura; os mesmos números produzem
 * sempre o mesmo resultado.
 */
public final class ChargeLines {

    private ChargeLines() {
    }

    /** Excedente cobrável sobre a franquia (zero quando o consumo não passa da franquia). */
    public static long excess(long quantity, long included) {
        return Math.max(0, quantity - included);
    }

    /**
     * Valor de {@code units} unidades a {@code unitPrice}, arredondado a centavos com {@link Money#ROUNDING}
     * (HALF_EVEN, o mesmo do restante do sistema) numa única operação — o preço unitário (até 6 casas) não é
     * arredondado antes.
     */
    public static BigDecimal amount(long units, BigDecimal unitPrice) {
        return BigDecimal.valueOf(units).multiply(unitPrice).setScale(Money.SCALE, Money.ROUNDING);
    }

    /** Uso da franquia em % (2 casas, HALF_EVEN); {@code null} quando a franquia é zero. */
    public static BigDecimal allowancePercent(long used, long included) {
        if (included == 0) {
            return null;
        }
        return BigDecimal.valueOf(used).multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(included), 2, RoundingMode.HALF_EVEN);
    }
}
