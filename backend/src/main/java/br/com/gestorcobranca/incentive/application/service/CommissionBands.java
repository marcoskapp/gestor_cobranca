package br.com.gestorcobranca.incentive.application.service;

import br.com.gestorcobranca.shared.domain.Money;

/**
 * Faixas de valor para eventos de negócio: remuneração individual nunca vai para logs (§7), só a
 * faixa. Limites fixos para que painéis sejam comparáveis no tempo.
 */
final class CommissionBands {

    private CommissionBands() {
    }

    static String of(Money amount) {
        if (amount == null || !amount.isPositive()) {
            return "0";
        }
        long reais = amount.amount().longValue();
        if (reais < 100) {
            return "0-100";
        }
        if (reais < 500) {
            return "100-500";
        }
        if (reais < 1_000) {
            return "500-1000";
        }
        if (reais < 3_000) {
            return "1000-3000";
        }
        if (reais < 10_000) {
            return "3000-10000";
        }
        return "10000+";
    }
}
