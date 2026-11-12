package br.com.gestorcobranca.costing.domain.service;

import br.com.gestorcobranca.costing.domain.model.CostCategory;
import br.com.gestorcobranca.costing.domain.model.CostFact;
import br.com.gestorcobranca.costing.domain.model.CostKey;
import br.com.gestorcobranca.costing.domain.model.CostWarningCode;
import br.com.gestorcobranca.costing.domain.model.Overhead;
import br.com.gestorcobranca.costing.domain.model.OverheadAllocation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Rateio de um custo fixo do mês (desenho §3.4, decisão §9 Q3) em fatos {@code OVERHEAD} diários:
 * <ul>
 *   <li>{@code HOURS}, {@code RECOVERED}, {@code CASES}: na proporção da base de cada dia × credor × carteira do mês
 *       (horas diretas + ociosas; recuperado bruto; casos-dia trabalhados). Só entra base com credor;</li>
 *   <li>{@code CREDITOR}: inteiro ao credor, em partes iguais pelos dias do mês, sem carteira;</li>
 *   <li>sem base no mês: o valor fica sem credor no primeiro dia do mês, com aviso {@code OVERHEAD_WITHOUT_BASIS}.</li>
 * </ul>
 * As partes são arredondadas em 6 casas e a diferença de arredondamento vai para a maior base: a soma das partes é
 * exatamente o valor do custo fixo.
 */
public final class OverheadAllocator {

    private static final int SCALE = 6;

    private OverheadAllocator() {
    }

    /**
     * @param basis base do mês por dia × credor × carteira, para a regra do custo ({@code HOURS}, {@code RECOVERED} ou
     *              {@code CASES}); ignorada em {@code CREDITOR}
     */
    public static void allocate(Overhead overhead, Map<CostKey, BigDecimal> basis, CostAccumulator acc) {
        YearMonth month = overhead.getMonth();
        BigDecimal amount = overhead.getAmount();
        if (overhead.getAllocation() == OverheadAllocation.CREDITOR) {
            int days = month.lengthOfMonth();
            BigDecimal perDay = amount.divide(BigDecimal.valueOf(days), SCALE, RoundingMode.HALF_EVEN);
            BigDecimal rest = amount.subtract(perDay.multiply(BigDecimal.valueOf(days)));
            for (int d = 1; d <= days; d++) {
                BigDecimal part = d == days ? perDay.add(rest) : perDay;
                add(acc, month.atDay(d), overhead.getCreditorId(), null, part);
            }
            return;
        }
        List<Map.Entry<CostKey, BigDecimal>> entries = basis.entrySet().stream()
                .filter(e -> e.getKey().creditorId() != null && e.getValue() != null && e.getValue().signum() > 0)
                .filter(e -> YearMonth.from(e.getKey().day()).equals(month))
                .sorted(Comparator.comparing((Map.Entry<CostKey, BigDecimal> e) -> e.getKey().day())
                        .thenComparing(e -> String.valueOf(e.getKey().creditorId()))
                        .thenComparing(e -> String.valueOf(e.getKey().portfolioId())))
                .toList();
        BigDecimal total = entries.stream().map(Map.Entry::getValue).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (total.signum() <= 0) {
            LocalDate first = month.atDay(1);
            add(acc, first, null, null, amount);
            acc.warn(first, null, null, CostWarningCode.OVERHEAD_WITHOUT_BASIS, BigDecimal.ONE);
            return;
        }
        BigDecimal allocated = BigDecimal.ZERO;
        Map.Entry<CostKey, BigDecimal> largest = entries.getFirst();
        BigDecimal[] parts = new BigDecimal[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            Map.Entry<CostKey, BigDecimal> e = entries.get(i);
            parts[i] = amount.multiply(e.getValue()).divide(total, SCALE, RoundingMode.HALF_EVEN);
            allocated = allocated.add(parts[i]);
            if (e.getValue().compareTo(largest.getValue()) > 0) {
                largest = e;
            }
        }
        BigDecimal rest = amount.subtract(allocated);
        for (int i = 0; i < entries.size(); i++) {
            CostKey k = entries.get(i).getKey();
            BigDecimal part = entries.get(i) == largest ? parts[i].add(rest) : parts[i];
            add(acc, k.day(), k.creditorId(), k.portfolioId(), part);
        }
    }

    private static void add(CostAccumulator acc, LocalDate day, UUID creditorId, UUID portfolioId, BigDecimal amount) {
        acc.add(day, creditorId, portfolioId, CostCategory.OVERHEAD, amount, amount, CostFact.Unit.BRL);
    }
}
