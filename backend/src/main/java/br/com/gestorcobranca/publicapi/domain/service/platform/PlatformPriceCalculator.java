package br.com.gestorcobranca.publicapi.domain.service.platform;

import br.com.gestorcobranca.publicapi.domain.model.BillableUsage;
import br.com.gestorcobranca.publicapi.domain.model.Proration;
import br.com.gestorcobranca.publicapi.domain.model.platform.PlatformPriceTerms;
import br.com.gestorcobranca.publicapi.domain.model.platform.VolumeTier;
import br.com.gestorcobranca.publicapi.domain.service.ChargeLines;
import br.com.gestorcobranca.shared.domain.Money;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Cálculo do extrato da plataforma para a assessoria num ciclo: função pura da versão da política da plataforma, das
 * quantidades faturáveis do ciclo (soma de todos os tokens da assessoria) e da proporcionalidade do ciclo — as mesmas
 * entradas dão sempre o mesmo extrato. Reaproveita as regras da cobrança ao credor ({@link ChargeLines},
 * {@link Proration}): franquia por ciclo, excedente, mensalidade e franquias proporcionais no primeiro, no último e no
 * de transição, centavos com HALF_EVEN por linha e total = soma das linhas.
 * <p>
 * <b>Faixas de volume das chamadas (preço graduado):</b> as faixas contam as chamadas do ciclo desde a primeira; as da
 * franquia não são cobradas e cada chamada excedente paga o preço da faixa em que cai. Exemplo: franquia de 10.000,
 * faixas até 100.000 a R$ 0,01 e acima a R$ 0,008; 150.000 chamadas = 90.000 × 0,01 + 50.000 × 0,008 = R$ 1.300,00.
 * No ciclo proporcional os limites das faixas também são proporcionais (arredondados para cima, como as franquias).
 * Cada faixa é arredondada a centavos (é uma sublinha visível no extrato) e a linha é a soma das faixas.
 */
public final class PlatformPriceCalculator {

    public static final String MONTHLY_FEE = "MONTHLY_FEE";
    public static final String REQUESTS = "REQUESTS";
    public static final String MB_IN = "MB_IN";
    public static final String MB_OUT = "MB_OUT";
    public static final String RECORDS = "RECORDS";

    private PlatformPriceCalculator() {
    }

    /**
     * Sublinha de uma faixa de volume.
     *
     * @param from  primeira chamada do ciclo na faixa (contagem acumulada)
     * @param to    última chamada da faixa (já proporcional no ciclo proporcional); {@code null} na última faixa
     * @param units chamadas excedentes cobradas na faixa
     */
    public record TierCharge(long from, Long to, long units, BigDecimal unitPrice, BigDecimal amount) {
    }

    /**
     * @param metric    MONTHLY_FEE, REQUESTS, MB_IN, MB_OUT ou RECORDS
     * @param quantity  quantidade faturável do ciclo (1 para a mensalidade)
     * @param included  franquia do ciclo (já proporcional no ciclo proporcional)
     * @param billable  excedente cobrado
     * @param unitPrice preço unitário; para a mensalidade, o valor cheio; {@code null} nas chamadas com faixas
     * @param amount    valor da linha (2 casas)
     * @param tiers     sublinhas das faixas de volume (vazio sem faixas)
     */
    public record Line(String metric, long quantity, long included, long billable, BigDecimal unitPrice,
                       BigDecimal amount, List<TierCharge> tiers) {
    }

    public record Result(List<Line> lines, BigDecimal total) {
    }

    public static Result calculate(PlatformPriceTerms terms, BillableUsage usage, Proration proration) {
        List<Line> lines = List.of(
                new Line(MONTHLY_FEE, 1, 0, 1, terms.monthlyFee(), proration.money(terms.monthlyFee()), List.of()),
                requests(terms, usage.requests(), proration),
                flat(MB_IN, usage.mbIn(), proration.allowance(terms.includedMbIn()), terms.pricePerMbIn()),
                flat(MB_OUT, usage.mbOut(), proration.allowance(terms.includedMbOut()), terms.pricePerMbOut()),
                flat(RECORDS, usage.records(), proration.allowance(terms.includedRecords()), terms.pricePerRecord()));
        BigDecimal total = lines.stream().map(Line::amount).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(Money.SCALE, Money.ROUNDING);
        return new Result(lines, total);
    }

    private static Line flat(String metric, long quantity, long included, BigDecimal unitPrice) {
        long billable = ChargeLines.excess(quantity, included);
        return new Line(metric, quantity, included, billable, unitPrice, ChargeLines.amount(billable, unitPrice),
                List.of());
    }

    private static Line requests(PlatformPriceTerms terms, long quantity, Proration proration) {
        long included = proration.allowance(terms.includedRequests());
        if (!terms.tieredRequests()) {
            return flat(REQUESTS, quantity, included, terms.pricePerRequest());
        }
        List<TierCharge> charges = new ArrayList<>();
        long lower = 0;
        BigDecimal amount = BigDecimal.ZERO.setScale(Money.SCALE);
        for (VolumeTier tier : terms.requestTiers()) {
            Long upper = tier.upTo() == null ? null : Math.max(lower, proration.allowance(tier.upTo()));
            long start = Math.max(lower, included);
            long end = upper == null ? quantity : Math.min(upper, quantity);
            long units = Math.max(0, end - start);
            BigDecimal tierAmount = ChargeLines.amount(units, tier.unitPrice());
            charges.add(new TierCharge(lower + 1, upper, units, tier.unitPrice(), tierAmount));
            amount = amount.add(tierAmount);
            if (upper == null) {
                break;
            }
            lower = upper;
        }
        return new Line(REQUESTS, quantity, included, ChargeLines.excess(quantity, included), null, amount,
                List.copyOf(charges));
    }
}
