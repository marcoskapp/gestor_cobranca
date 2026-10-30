package br.com.gestorcobranca.publicapi.domain.service;

import br.com.gestorcobranca.publicapi.domain.model.PriceTerms;
import br.com.gestorcobranca.publicapi.domain.model.UsageTotals;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Cálculo do extrato de consumo: função pura das condições da tabela e do consumo do mês — o mesmo consumo e a mesma
 * versão da tabela produzem sempre o mesmo extrato (reprodutível). Cada medida tem franquia mensal e preço do excedente;
 * megabytes são decimais (1 MB = 1.000.000 bytes) com fração proporcional; o arredondamento a centavos (HALF_EVEN) é
 * feito por linha, e o total é a soma das linhas.
 */
public final class PriceCalculator {

    static final BigDecimal BYTES_PER_MB = BigDecimal.valueOf(1_000_000L);

    private PriceCalculator() {
    }

    /**
     * @param metric   REQUESTS, MB_IN, MB_OUT, RECORDS ou MONTHLY_FEE
     * @param quantity consumo medido (requisições cobráveis, MB, registros; 1 para a mensalidade)
     * @param included franquia
     * @param billable excedente cobrado
     * @param amount   valor da linha (2 casas)
     */
    public record Line(String metric, BigDecimal quantity, BigDecimal included, BigDecimal billable, BigDecimal unitPrice,
                       BigDecimal amount) {
    }

    public record Result(List<Line> lines, BigDecimal total) {
    }

    public static Result calculate(PriceTerms terms, UsageTotals usage) {
        BigDecimal mbIn = megabytes(usage.bytesIn());
        BigDecimal mbOut = megabytes(usage.bytesOut());
        List<Line> lines = List.of(
                new Line("MONTHLY_FEE", BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, terms.monthlyFee(),
                        terms.monthlyFee().setScale(2, RoundingMode.HALF_EVEN)),
                line("REQUESTS", BigDecimal.valueOf(usage.billableRequests()), terms.includedRequests(), terms.pricePerRequest()),
                line("MB_IN", mbIn, terms.includedMbIn(), terms.pricePerMbIn()),
                line("MB_OUT", mbOut, terms.includedMbOut(), terms.pricePerMbOut()),
                line("RECORDS", BigDecimal.valueOf(usage.records()), terms.includedRecords(), terms.pricePerRecord()));
        BigDecimal total = lines.stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_EVEN);
        return new Result(lines, total);
    }

    static BigDecimal megabytes(long bytes) {
        return BigDecimal.valueOf(bytes).divide(BYTES_PER_MB, 6, RoundingMode.HALF_EVEN);
    }

    private static Line line(String metric, BigDecimal quantity, long included, BigDecimal unitPrice) {
        BigDecimal allowance = BigDecimal.valueOf(included);
        BigDecimal billable = quantity.subtract(allowance).max(BigDecimal.ZERO);
        BigDecimal amount = billable.multiply(unitPrice).setScale(2, RoundingMode.HALF_EVEN);
        return new Line(metric, quantity, allowance, billable, unitPrice, amount);
    }
}
