package br.com.gestorcobranca.publicapi.domain.service;

import br.com.gestorcobranca.publicapi.domain.model.BillableUsage;
import br.com.gestorcobranca.publicapi.domain.model.PriceTerms;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Cálculo do extrato de um ciclo: função pura das condições de preço do contrato e das quantidades faturáveis do
 * ciclo — as mesmas condições e o mesmo consumo produzem sempre o mesmo extrato (reprodutível). Todas as quantidades
 * são inteiras (requisições, MB, registros); cada medida tem franquia por ciclo e preço do excedente; o arredondamento
 * a centavos (HALF_EVEN) é feito por linha, e o total é a soma das linhas.
 */
public final class PriceCalculator {

    private PriceCalculator() {
    }

    /**
     * @param metric   CYCLE_FEE, REQUESTS, MB_IN, MB_OUT ou RECORDS
     * @param quantity quantidade faturável do ciclo (1 para a mensalidade)
     * @param included franquia
     * @param billable excedente cobrado
     * @param amount   valor da linha (2 casas)
     */
    public record Line(String metric, long quantity, long included, long billable, BigDecimal unitPrice,
                       BigDecimal amount) {
    }

    public record Result(List<Line> lines, BigDecimal total) {
    }

    public static Result calculate(PriceTerms terms, BillableUsage usage) {
        List<Line> lines = List.of(
                new Line("MONTHLY_FEE", 1, 0, 1, terms.monthlyFee(), terms.monthlyFee().setScale(2, RoundingMode.HALF_EVEN)),
                line("REQUESTS", usage.requests(), terms.includedRequests(), terms.pricePerRequest()),
                line("MB_IN", usage.mbIn(), terms.includedMbIn(), terms.pricePerMbIn()),
                line("MB_OUT", usage.mbOut(), terms.includedMbOut(), terms.pricePerMbOut()),
                line("RECORDS", usage.records(), terms.includedRecords(), terms.pricePerRecord()));
        BigDecimal total = lines.stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_EVEN);
        return new Result(lines, total);
    }

    private static Line line(String metric, long quantity, long included, BigDecimal unitPrice) {
        long billable = Math.max(0, quantity - included);
        BigDecimal amount = BigDecimal.valueOf(billable).multiply(unitPrice).setScale(2, RoundingMode.HALF_EVEN);
        return new Line(metric, quantity, included, billable, unitPrice, amount);
    }
}
