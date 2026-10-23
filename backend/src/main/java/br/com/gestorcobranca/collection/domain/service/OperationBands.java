package br.com.gestorcobranca.collection.domain.service;

import br.com.gestorcobranca.shared.domain.Money;

import java.math.BigDecimal;

/**
 * Faixas de atraso e de valor usadas como dimensões nos eventos de negócio da operação (sem
 * expor valores individuais em painéis agregados). Os rótulos são contrato com os painéis —
 * não renomear.
 */
public final class OperationBands {

    private static final BigDecimal B500 = BigDecimal.valueOf(500);
    private static final BigDecimal B1K = BigDecimal.valueOf(1_000);
    private static final BigDecimal B5K = BigDecimal.valueOf(5_000);
    private static final BigDecimal B20K = BigDecimal.valueOf(20_000);

    private OperationBands() {
    }

    /** {@code 0-30}, {@code 31-90}, {@code 91-180}, {@code 181-360}, {@code 361+}. */
    public static String overdueBand(long daysOverdue) {
        if (daysOverdue <= 30) {
            return "0-30";
        }
        if (daysOverdue <= 90) {
            return "31-90";
        }
        if (daysOverdue <= 180) {
            return "91-180";
        }
        if (daysOverdue <= 360) {
            return "181-360";
        }
        return "361+";
    }

    /** {@code 0-500}, {@code 500-1k}, {@code 1k-5k}, {@code 5k-20k}, {@code 20k+} (limite inferior inclusivo). */
    public static String valueBand(Money value) {
        BigDecimal v = value == null ? BigDecimal.ZERO : value.amount();
        if (v.compareTo(B500) < 0) {
            return "0-500";
        }
        if (v.compareTo(B1K) < 0) {
            return "500-1k";
        }
        if (v.compareTo(B5K) < 0) {
            return "1k-5k";
        }
        if (v.compareTo(B20K) < 0) {
            return "5k-20k";
        }
        return "20k+";
    }
}
