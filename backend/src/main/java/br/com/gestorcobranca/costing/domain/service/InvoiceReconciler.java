package br.com.gestorcobranca.costing.domain.service;

import br.com.gestorcobranca.costing.domain.model.ChannelItem;
import br.com.gestorcobranca.costing.domain.model.CostFact;
import br.com.gestorcobranca.costing.domain.model.CostKey;
import br.com.gestorcobranca.costing.domain.model.CostWarningCode;
import br.com.gestorcobranca.costing.domain.model.ItemEstimate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Ajuste do custo de canal pela fatura real (desenho §3.5 e §13). Para a fatura de um item no mês:
 * <ul>
 *   <li>ajuste = fatura − estimativa do item no mês (pode ser negativo: a estimativa passou da fatura);</li>
 *   <li>rateado na proporção do <b>custo estimado</b> do item em cada dia × credor × carteira do mês — inclusive as
 *       linhas sem credor (ex.: chamada sem credor), que recebem o ajuste delas;</li>
 *   <li>estimativa zero no mês (ex.: sem tarifa): rateado pela <b>quantidade de uso</b> do item (minutos,
 *       conversas/mensagens, dólares de IA);</li>
 *   <li>sem uso nenhum: o valor inteiro fica sem credor no primeiro dia do mês, com aviso
 *       {@code INVOICE_WITHOUT_USAGE}.</li>
 * </ul>
 * Partes arredondadas em 6 casas; a diferença de arredondamento vai para a maior base (como o rateio dos custos fixos):
 * a soma das partes é exatamente o ajuste, e estimativa + ajuste = fatura. Os fatos do ajuste ficam na categoria do
 * item, com o item, em reais e quantidade zero (o uso já está na estimativa).
 */
public final class InvoiceReconciler {

    private static final int SCALE = 6;

    private InvoiceReconciler() {
    }

    /**
     * @param month    mês da fatura
     * @param item     item faturado
     * @param invoice  valor da fatura em R$ (zero ou mais)
     * @param estimate estimativa do item por dia × credor × carteira (só os dias do mês entram)
     * @param acc      recebe os fatos do ajuste e o aviso
     * @return o ajuste total (fatura − estimativa do mês)
     */
    public static BigDecimal reconcile(YearMonth month, ChannelItem item, BigDecimal invoice,
                                       Map<CostKey, ItemEstimate> estimate, CostAccumulator acc) {
        List<Map.Entry<CostKey, ItemEstimate>> entries = estimate.entrySet().stream()
                .filter(e -> e.getValue() != null && YearMonth.from(e.getKey().day()).equals(month))
                .sorted(Comparator.comparing((Map.Entry<CostKey, ItemEstimate> e) -> e.getKey().day())
                        .thenComparing(e -> String.valueOf(e.getKey().creditorId()))
                        .thenComparing(e -> String.valueOf(e.getKey().portfolioId())))
                .toList();
        BigDecimal estimated = sum(entries, ItemEstimate::amount);
        BigDecimal adjustment = invoice.subtract(estimated);
        // pela estimativa; sem estimativa (ex.: sem tarifa), pela quantidade de uso
        Function<ItemEstimate, BigDecimal> weight = estimated.signum() > 0 ? ItemEstimate::amount
                : ItemEstimate::quantity;
        List<Map.Entry<CostKey, ItemEstimate>> basis = entries.stream()
                .filter(e -> weight.apply(e.getValue()).signum() > 0).toList();
        BigDecimal total = sum(basis, weight);
        if (total.signum() <= 0) {
            if (adjustment.signum() != 0) {
                LocalDate first = month.atDay(1);
                add(acc, first, null, null, item, adjustment);
                acc.warn(first, null, null, CostWarningCode.INVOICE_WITHOUT_USAGE, BigDecimal.ONE);
            }
            return adjustment;
        }
        if (adjustment.signum() == 0) {
            return adjustment;
        }
        BigDecimal[] parts = new BigDecimal[basis.size()];
        BigDecimal allocated = BigDecimal.ZERO;
        int largest = 0;
        for (int i = 0; i < basis.size(); i++) {
            BigDecimal w = weight.apply(basis.get(i).getValue());
            parts[i] = adjustment.multiply(w).divide(total, SCALE, RoundingMode.HALF_EVEN);
            allocated = allocated.add(parts[i]);
            if (w.compareTo(weight.apply(basis.get(largest).getValue())) > 0) {
                largest = i;
            }
        }
        parts[largest] = parts[largest].add(adjustment.subtract(allocated));
        for (int i = 0; i < basis.size(); i++) {
            CostKey k = basis.get(i).getKey();
            add(acc, k.day(), k.creditorId(), k.portfolioId(), item, parts[i]);
        }
        return adjustment;
    }

    private static BigDecimal sum(List<Map.Entry<CostKey, ItemEstimate>> entries,
                                  Function<ItemEstimate, BigDecimal> value) {
        return entries.stream().map(e -> value.apply(e.getValue())).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static void add(CostAccumulator acc, LocalDate day, UUID creditorId, UUID portfolioId, ChannelItem item,
                            BigDecimal amount) {
        acc.add(day, creditorId, portfolioId, item.category(), amount, BigDecimal.ZERO, CostFact.Unit.BRL, item);
    }
}
