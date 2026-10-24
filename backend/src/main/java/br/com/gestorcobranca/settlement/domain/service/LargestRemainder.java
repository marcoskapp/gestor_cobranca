package br.com.gestorcobranca.settlement.domain.service;

import br.com.gestorcobranca.shared.domain.Money;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Distribui um valor entre partes proporcionalmente aos pesos, em centavos, pelo método dos maiores
 * restos (a soma é exata). Usado para repartir um estorno entre os recebimentos de uma alocação.
 */
public final class LargestRemainder {

    private LargestRemainder() {
    }

    /** @param weights pesos positivos; {@code total} ≤ soma dos pesos */
    public static List<Money> split(Money total, List<Money> weights) {
        long totalCents = total.toCents();
        long weightSum = weights.stream().mapToLong(Money::toCents).sum();
        if (weights.isEmpty() || weightSum == 0) {
            return weights.stream().map(w -> Money.ZERO).toList();
        }
        long[] base = new long[weights.size()];
        long[] remainders = new long[weights.size()];
        long assigned = 0;
        for (int i = 0; i < weights.size(); i++) {
            long numerator = Math.multiplyExact(totalCents, weights.get(i).toCents());
            base[i] = numerator / weightSum;
            remainders[i] = numerator % weightSum;
            assigned += base[i];
        }
        long left = totalCents - assigned;
        List<Integer> order = new ArrayList<>(IntStream.range(0, weights.size()).boxed().toList());
        order.sort(Comparator.<Integer>comparingLong(i -> remainders[i]).reversed().thenComparingInt(i -> i));
        for (int k = 0; k < left; k++) {
            base[order.get(k)]++;
        }
        return IntStream.range(0, weights.size()).mapToObj(i -> Money.ofCents(base[i])).toList();
    }
}
