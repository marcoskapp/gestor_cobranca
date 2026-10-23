package br.com.gestorcobranca.negotiation.domain.service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Aritmética decimal exata para fatores de juros e correção com expoente fracionário
 * ({@code (1 + i)^(d/30)}), sem passar por {@code double}. Precisão de 34 dígitos significativos
 * ({@link MathContext#DECIMAL128}); o arredondamento para centavos acontece só no valor final de
 * cada componente.
 */
public final class DecimalMath {

    public static final MathContext MC = MathContext.DECIMAL128;
    /** Escala usada para exibir fatores na memória de cálculo. */
    public static final int FACTOR_DISPLAY_SCALE = 10;

    private static final BigDecimal TOLERANCE = new BigDecimal("1E-32");

    private DecimalMath() {
    }

    /** {@code base^(numerator/denominator)} para base positiva; o expoente pode ser negativo. */
    public static BigDecimal pow(BigDecimal base, long numerator, long denominator) {
        if (base.signum() <= 0) {
            throw new IllegalArgumentException("Base deve ser positiva: " + base);
        }
        if (denominator <= 0) {
            throw new IllegalArgumentException("Denominador deve ser positivo");
        }
        if (numerator == 0) {
            return BigDecimal.ONE;
        }
        long gcd = gcd(Math.abs(numerator), denominator);
        long num = numerator / gcd;
        long den = denominator / gcd;
        BigDecimal root = den == 1 ? base : nthRoot(base, Math.toIntExact(den));
        BigDecimal result = root.pow(Math.toIntExact(Math.abs(num)), MC);
        return num < 0 ? BigDecimal.ONE.divide(result, MC) : result;
    }

    /** Raiz n-ésima por Newton–Raphson ({@code x ← ((n−1)x + a/x^(n−1)) / n}). */
    static BigDecimal nthRoot(BigDecimal a, int n) {
        if (n == 1) {
            return a;
        }
        BigDecimal bn = BigDecimal.valueOf(n);
        BigDecimal nMinus1 = BigDecimal.valueOf(n - 1L);
        BigDecimal x = new BigDecimal(Math.pow(a.doubleValue(), 1.0 / n), MC);
        for (int i = 0; i < 200; i++) {
            BigDecimal next = nMinus1.multiply(x, MC).add(a.divide(x.pow(n - 1, MC), MC), MC).divide(bn, MC);
            if (next.subtract(x, MC).abs().compareTo(TOLERANCE) <= 0) {
                return next;
            }
            x = next;
        }
        return x;
    }

    /** Percentual (2,5) em fração (0,025) com precisão total. */
    public static BigDecimal fraction(BigDecimal percent) {
        return percent.divide(BigDecimal.valueOf(100), MC);
    }

    /** Fator para exibição na memória de cálculo (10 casas, HALF_EVEN). */
    public static BigDecimal display(BigDecimal factor) {
        return factor.setScale(FACTOR_DISPLAY_SCALE, RoundingMode.HALF_EVEN);
    }

    private static long gcd(long a, long b) {
        return b == 0 ? a : gcd(b, a % b);
    }
}
