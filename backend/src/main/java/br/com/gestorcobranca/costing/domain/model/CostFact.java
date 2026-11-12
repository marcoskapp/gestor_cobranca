package br.com.gestorcobranca.costing.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Fato de custo apurado (linha de {@code costing.cost_daily}): dia × credor × carteira × categoria, com o valor em R$
 * (6 casas), a quantidade (4 casas) e a unidade da quantidade. Sem dado pessoal: nem usuário, nem devedor. Credor
 * nulo = valor da assessoria sem credor (visível só a quem vê todos os credores).
 */
public record CostFact(LocalDate day, UUID creditorId, UUID portfolioId, CostCategory category, BigDecimal amount,
                       BigDecimal quantity, Unit unit) {

    /** Unidade da quantidade do fato. */
    public enum Unit { HOURS, MINUTES, UNITS, USD, ENTRIES, BRL }

    public CostKey key() {
        return new CostKey(day, creditorId, portfolioId);
    }
}
