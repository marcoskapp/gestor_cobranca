package br.com.gestorcobranca.publicapi.domain.service;

import br.com.gestorcobranca.publicapi.domain.model.BillableUsage;
import br.com.gestorcobranca.publicapi.domain.model.PriceTerms;
import br.com.gestorcobranca.publicapi.domain.model.Proration;
import br.com.gestorcobranca.shared.domain.Money;

import java.math.BigDecimal;
import java.util.List;

/**
 * Cálculo do extrato de um ciclo: função pura das condições de preço do contrato, das quantidades faturáveis do
 * ciclo e da proporcionalidade do ciclo — as mesmas entradas produzem sempre o mesmo extrato (reprodutível). Todas as
 * quantidades são inteiras (requisições, MB, registros); cada medida tem franquia por ciclo e preço do excedente; no
 * ciclo de transição a mensalidade e as franquias são proporcionais aos dias ({@link Proration}). O arredondamento a
 * centavos (HALF_EVEN, {@link Money#ROUNDING}) é feito por linha, e o total é a soma das linhas.
 */
public final class PriceCalculator {

    private PriceCalculator() {
    }

    /**
     * @param metric    MONTHLY_FEE, REQUESTS, MB_IN, MB_OUT ou RECORDS
     * @param quantity  quantidade faturável do ciclo (1 para a mensalidade)
     * @param included  franquia do ciclo (já proporcional no ciclo de transição)
     * @param billable  excedente cobrado
     * @param unitPrice preço unitário do contrato (para a mensalidade, o valor cheio)
     * @param amount    valor da linha (2 casas; mensalidade proporcional no ciclo de transição)
     */
    public record Line(String metric, long quantity, long included, long billable, BigDecimal unitPrice,
                       BigDecimal amount) {
    }

    public record Result(List<Line> lines, BigDecimal total) {
    }

    public static Result calculate(PriceTerms terms, BillableUsage usage) {
        return calculate(terms, usage, Proration.FULL);
    }

    public static Result calculate(PriceTerms terms, BillableUsage usage, Proration proration) {
        List<Line> lines = List.of(
                new Line("MONTHLY_FEE", 1, 0, 1, terms.monthlyFee(), proration.money(terms.monthlyFee())),
                line("REQUESTS", usage.requests(), proration.allowance(terms.includedRequests()), terms.pricePerRequest()),
                line("MB_IN", usage.mbIn(), proration.allowance(terms.includedMbIn()), terms.pricePerMbIn()),
                line("MB_OUT", usage.mbOut(), proration.allowance(terms.includedMbOut()), terms.pricePerMbOut()),
                line("RECORDS", usage.records(), proration.allowance(terms.includedRecords()), terms.pricePerRecord()));
        BigDecimal total = lines.stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, Money.ROUNDING);
        return new Result(lines, total);
    }

    private static Line line(String metric, long quantity, long included, BigDecimal unitPrice) {
        long billable = Math.max(0, quantity - included);
        BigDecimal amount = BigDecimal.valueOf(billable).multiply(unitPrice).setScale(2, Money.ROUNDING);
        return new Line(metric, quantity, included, billable, unitPrice, amount);
    }
}
