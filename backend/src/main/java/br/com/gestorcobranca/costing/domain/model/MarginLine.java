package br.com.gestorcobranca.costing.domain.model;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Linha de margem (contrato §10.2, {@code Line}): valores em R$ com 2 casas, taxas como fração com 4 casas (0,2500 =
 * 25%), custo por real recuperado com 4 casas e horas com 2. Taxa sem base (denominador zero ou negativo) = nula.
 *
 * @param costs custo por categoria (todas as categorias, zero quando não houver)
 */
@org.springframework.modulith.NamedInterface("usecases")
public record MarginLine(BigDecimal recovered, BigDecimal revenue, Map<CostCategory, BigDecimal> costs,
                         BigDecimal directCost, BigDecimal totalCost, BigDecimal contributionMargin,
                         BigDecimal netMargin, BigDecimal contributionMarginRate, BigDecimal netMarginRate,
                         BigDecimal roi, BigDecimal costPerRecovered, long settledAgreements,
                         BigDecimal costPerSettledAgreement, BigDecimal directHours, BigDecimal idleHours) {
}
