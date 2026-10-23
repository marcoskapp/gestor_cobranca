package br.com.gestorcobranca.payment.domain.service;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Rateio de um valor proporcional a pesos pelo método dos maiores restos (em centavos): a soma das
 * partes é exatamente o total e nenhuma parte excede o próprio peso quando o total não excede a soma.
 */
public final class LargestRemainder {

    private LargestRemainder() {
    }

    public static List<Money> split(Money total, List<Money> weights) {
        Guard.require(total != null && !total.isNegative(), "split.total_invalid", "Total do rateio inválido");
        long totalCents = total.toCents();
        long[] cents = weights.stream().mapToLong(w -> Math.max(0, w.toCents())).toArray();
        long sum = 0;
        for (long c : cents) {
            sum += c;
        }
        List<Money> result = new ArrayList<>(cents.length);
        if (sum == 0 || totalCents == 0) {
            IntStream.range(0, cents.length).forEach(i -> result.add(Money.ZERO));
            return result;
        }
        long[] parts = new long[cents.length];
        BigDecimal[] remainders = new BigDecimal[cents.length];
        long distributed = 0;
        for (int i = 0; i < cents.length; i++) {
            BigDecimal exact = BigDecimal.valueOf(totalCents).multiply(BigDecimal.valueOf(cents[i]))
                    .divide(BigDecimal.valueOf(sum), 10, RoundingMode.DOWN);
            parts[i] = exact.setScale(0, RoundingMode.DOWN).longValueExact();
            remainders[i] = exact.subtract(BigDecimal.valueOf(parts[i]));
            distributed += parts[i];
        }
        long missing = totalCents - distributed;
        List<Integer> order = IntStream.range(0, cents.length).boxed()
                .sorted(Comparator.<Integer, BigDecimal>comparing(i -> remainders[i]).reversed()
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
        for (int k = 0; missing > 0 && k < order.size(); k++) {
            int i = order.get(k);
            if (cents[i] > 0) {
                parts[i]++;
                missing--;
            }
        }
        for (long p : parts) {
            result.add(Money.ofCents(p));
        }
        return result;
    }
}
