package br.com.gestorcobranca.analytics.domain.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

/**
 * Resultado de um recorte da política de negociação (carteira × faixa de atraso) no período — só agregados, sem dado
 * pessoal (decisão 1087).
 *
 * @param portfolioId         carteira (nula = total do credor)
 * @param delayBucket         faixa de atraso ({@code D0_30} … {@code D361_PLUS}, {@code UNKNOWN}; nula = todas)
 * @param agreements          acordos formalizados no período
 * @param matureAgreements    acordos com ao menos 60 dias (expostos ao risco de quebra precoce)
 * @param brokenEarly         maduros quebrados em até 60 dias
 * @param agreedAmount        valor total dos acordos
 * @param paidAmount          recebido desses acordos (líquido de estornos)
 * @param envelopeUsageSum    soma do uso do desconto máximo (%, 0–100) dos acordos com o dado
 * @param envelopeUsageCount  acordos com o uso do desconto informado
 * @param highEnvelopeCount   acordos que usaram 90% ou mais do desconto máximo
 * @param portalSimulations   consultas de condições no portal
 * @param portalWithoutOffer  consultas sem nenhuma oferta dentro da alçada do portal
 * @param cannotAfford        pedidos "nenhuma condição cabe no meu orçamento"
 */
public record PolicySegment(UUID portfolioId, String delayBucket, long agreements, long matureAgreements,
                            long brokenEarly, BigDecimal agreedAmount, BigDecimal paidAmount, BigDecimal envelopeUsageSum,
                            long envelopeUsageCount, long highEnvelopeCount, long portalSimulations,
                            long portalWithoutOffer, long cannotAfford) {

    public PolicySegment {
        agreedAmount = agreedAmount == null ? BigDecimal.ZERO : agreedAmount;
        paidAmount = paidAmount == null ? BigDecimal.ZERO : paidAmount;
        envelopeUsageSum = envelopeUsageSum == null ? BigDecimal.ZERO : envelopeUsageSum;
    }

    public static PolicySegment empty(UUID portfolioId, String delayBucket) {
        return new PolicySegment(portfolioId, delayBucket, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0,
                0, 0, 0);
    }

    /** Soma de dois recortes (o total do credor é a soma das carteiras e faixas). */
    public PolicySegment plus(PolicySegment o) {
        return new PolicySegment(portfolioId, delayBucket, agreements + o.agreements, matureAgreements + o.matureAgreements,
                brokenEarly + o.brokenEarly, agreedAmount.add(o.agreedAmount), paidAmount.add(o.paidAmount),
                envelopeUsageSum.add(o.envelopeUsageSum), envelopeUsageCount + o.envelopeUsageCount,
                highEnvelopeCount + o.highEnvelopeCount, portalSimulations + o.portalSimulations,
                portalWithoutOffer + o.portalWithoutOffer, cannotAfford + o.cannotAfford);
    }

    /** O credor sem este recorte (base de comparação das sugestões). */
    public PolicySegment minus(PolicySegment o) {
        return new PolicySegment(portfolioId, delayBucket, agreements - o.agreements, matureAgreements - o.matureAgreements,
                brokenEarly - o.brokenEarly, agreedAmount.subtract(o.agreedAmount), paidAmount.subtract(o.paidAmount),
                envelopeUsageSum.subtract(o.envelopeUsageSum), envelopeUsageCount - o.envelopeUsageCount,
                highEnvelopeCount - o.highEnvelopeCount, portalSimulations - o.portalSimulations,
                portalWithoutOffer - o.portalWithoutOffer, cannotAfford - o.cannotAfford);
    }

    /** Quebra precoce (até 60 dias) entre os maduros; nula sem maduros. */
    public BigDecimal earlyBreakRate() {
        return ratio(brokenEarly, matureAgreements);
    }

    /** Pago sobre acordado; nulo sem valor acordado. */
    public BigDecimal paidOverAgreed() {
        return agreedAmount.signum() == 0 ? null : paidAmount.divide(agreedAmount, 4, RoundingMode.HALF_EVEN);
    }

    /** Uso médio do desconto máximo (%, 0–100); nulo sem o dado. */
    public BigDecimal averageEnvelopeUsage() {
        return envelopeUsageCount == 0 ? null
                : envelopeUsageSum.divide(BigDecimal.valueOf(envelopeUsageCount), 2, RoundingMode.HALF_EVEN);
    }

    /** Fatia dos acordos que usaram 90% ou mais do desconto máximo; nula sem o dado. */
    public BigDecimal highEnvelopeShare() {
        return ratio(highEnvelopeCount, envelopeUsageCount);
    }

    /** Fatia das consultas no portal sem nenhuma oferta; nula sem consultas. */
    public BigDecimal portalWithoutOfferShare() {
        return ratio(portalWithoutOffer, portalSimulations);
    }

    private static BigDecimal ratio(long part, long whole) {
        return whole == 0 ? null : BigDecimal.valueOf(part).divide(BigDecimal.valueOf(whole), 4, RoundingMode.HALF_EVEN);
    }
}
