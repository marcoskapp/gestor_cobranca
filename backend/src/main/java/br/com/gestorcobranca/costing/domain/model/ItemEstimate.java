package br.com.gestorcobranca.costing.domain.model;

import java.math.BigDecimal;

/**
 * Estimativa de um item de custo de canal num corte (dia × credor × carteira, ou o mês inteiro): valor em R$ e
 * quantidade de uso na unidade do item (minutos, conversas/mensagens ou dólares de IA).
 */
public record ItemEstimate(BigDecimal amount, BigDecimal quantity) {

    public static final ItemEstimate ZERO = new ItemEstimate(BigDecimal.ZERO, BigDecimal.ZERO);

    public ItemEstimate {
        amount = amount == null ? BigDecimal.ZERO : amount;
        quantity = quantity == null ? BigDecimal.ZERO : quantity;
    }

    public ItemEstimate plus(ItemEstimate other) {
        return new ItemEstimate(amount.add(other.amount), quantity.add(other.quantity));
    }

    /** Houve uso: valor ou quantidade positivos. */
    public boolean used() {
        return amount.signum() > 0 || quantity.signum() > 0;
    }
}
