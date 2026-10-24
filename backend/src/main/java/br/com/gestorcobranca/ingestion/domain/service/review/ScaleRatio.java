package br.com.gestorcobranca.ingestion.domain.service.review;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.OptionalInt;

/** Razão entre dois valores próxima de uma potência de 10 — a assinatura de um separador decimal mal lido. */
public final class ScaleRatio {

    /** Expoentes considerados: ×10, ×100, ×1000 e as divisões correspondentes. */
    public static final int MAX_EXPONENT = 3;

    private ScaleRatio() {
    }

    /**
     * @param tolerance desvio relativo aceito em torno de 10ⁿ (ex.: 0,005 = ±0,5%)
     * @return {@code n} (±1..±3) tal que {@code a ÷ b ≈ 10ⁿ}, ou vazio
     */
    public static OptionalInt exponent(BigDecimal a, BigDecimal b, double tolerance) {
        if (a == null || b == null || a.signum() <= 0 || b.signum() <= 0) {
            return OptionalInt.empty();
        }
        return exponent(a.divide(b, MathContext.DECIMAL64).doubleValue(), tolerance);
    }

    public static OptionalInt exponent(double ratio, double tolerance) {
        if (!(ratio > 0) || !Double.isFinite(ratio)) {
            return OptionalInt.empty();
        }
        for (int n = -MAX_EXPONENT; n <= MAX_EXPONENT; n++) {
            if (n == 0) {
                continue;
            }
            double target = Math.pow(10, n);
            if (Math.abs(ratio / target - 1) <= tolerance) {
                return OptionalInt.of(n);
            }
        }
        return OptionalInt.empty();
    }
}
