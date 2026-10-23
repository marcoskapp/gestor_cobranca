package br.com.gestorcobranca.negotiation.domain.service;

import br.com.gestorcobranca.shared.domain.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Rateio de um valor em centavos proporcional a pesos, pelo método dos maiores restos: cada parte
 * recebe o piso de {@code valor × peso ÷ Σpesos}; os centavos restantes vão, um a um, às partes de
 * maior resto (empate: ordem de entrada). A soma é exatamente o valor e nenhuma parte excede o
 * seu peso quando {@code valor ≤ Σpesos}. Puro e determinístico.
 */
public final class ProportionalSplit {

    private ProportionalSplit() {
    }

    public static List<Money> split(Money value, List<Money> weights) {
        long total = weights.stream().mapToLong(Money::toCents).sum();
        long cents = value.toCents();
        List<Money> result = new ArrayList<>(weights.size());
        if (weights.isEmpty()) {
            return result;
        }
        if (total <= 0 || cents <= 0) {
            weights.forEach(w -> result.add(Money.ZERO));
            if (cents > 0) {
                // sem pesos: tudo na primeira parte
                result.set(0, value);
            }
            return result;
        }
        long[] parts = new long[weights.size()];
        BigDecimal[] remainders = new BigDecimal[weights.size()];
        long assigned = 0;
        for (int i = 0; i < weights.size(); i++) {
            BigDecimal exact = BigDecimal.valueOf(cents).multiply(BigDecimal.valueOf(weights.get(i).toCents()))
                    .divide(BigDecimal.valueOf(total), 10, RoundingMode.DOWN);
            parts[i] = exact.setScale(0, RoundingMode.DOWN).longValueExact();
            remainders[i] = exact.subtract(BigDecimal.valueOf(parts[i]));
            assigned += parts[i];
        }
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < weights.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparing((Integer i) -> remainders[i]).reversed().thenComparing(i -> i));
        long leftover = cents - assigned;
        for (int k = 0; leftover > 0; k = (k + 1) % order.size()) {
            int i = order.get(k);
            if (cents > total || parts[i] < weights.get(i).toCents()) {
                parts[i]++;
                leftover--;
            }
        }
        for (long part : parts) {
            result.add(Money.ofCents(part));
        }
        return result;
    }
}
