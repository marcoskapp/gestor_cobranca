package br.com.gestorcobranca.negotiation.domain.model.agreement;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;

/**
 * Resumo dos acordos de um devedor ou de um caso (histórico de acordos, desenho
 * {@code acordos-ficha-lateral-e-atencao-especial.md} §A1): quantidade por situação, total negociado e total pago.
 *
 * <p>Cumprimento = quitados ÷ (quitados + quebrados): o critério de sucesso é o acordo que o devedor cumpre.
 * Renegociados e cancelados ficam fora (o desfecho do renegociado está no acordo seguinte; o cancelamento não depende
 * do devedor). Nulo enquanto nenhum acordo foi quitado nem quebrado.
 *
 * @param fulfillmentRate fração de 0 a 1 com 4 casas, ou nulo
 */
public record AgreementSummary(long total, long active, long settled, long broken, long cancelled, long renegotiated,
                               BigDecimal totalAmount, BigDecimal paidAmount, BigDecimal fulfillmentRate) {

    /** Totais de uma situação (linha da consulta agrupada). */
    public record StatusTotals(AgreementStatus status, long count, BigDecimal totalAmount, BigDecimal paidAmount) {
    }

    public static AgreementSummary of(Collection<StatusTotals> rows) {
        Map<AgreementStatus, Long> counts = new EnumMap<>(AgreementStatus.class);
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal paid = BigDecimal.ZERO;
        long all = 0;
        for (StatusTotals r : rows) {
            counts.merge(r.status(), r.count(), Long::sum);
            all += r.count();
            total = total.add(r.totalAmount() == null ? BigDecimal.ZERO : r.totalAmount());
            paid = paid.add(r.paidAmount() == null ? BigDecimal.ZERO : r.paidAmount());
        }
        long settled = counts.getOrDefault(AgreementStatus.SETTLED, 0L);
        long broken = counts.getOrDefault(AgreementStatus.BROKEN, 0L);
        long active = counts.getOrDefault(AgreementStatus.FORMALIZED, 0L) + counts.getOrDefault(AgreementStatus.IN_PAYMENT, 0L);
        BigDecimal rate = settled + broken == 0 ? null
                : BigDecimal.valueOf(settled).divide(BigDecimal.valueOf(settled + broken), 4, RoundingMode.HALF_UP);
        return new AgreementSummary(all, active, settled, broken, counts.getOrDefault(AgreementStatus.CANCELLED, 0L),
                counts.getOrDefault(AgreementStatus.RENEGOTIATED, 0L), total.setScale(2, RoundingMode.HALF_UP),
                paid.setScale(2, RoundingMode.HALF_UP), rate);
    }
}
