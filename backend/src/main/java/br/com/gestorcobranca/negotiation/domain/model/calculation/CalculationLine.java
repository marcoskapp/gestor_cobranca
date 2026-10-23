package br.com.gestorcobranca.negotiation.domain.model.calculation;

import br.com.gestorcobranca.shared.domain.Money;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Linha da memória de cálculo: como um valor de componente foi obtido.
 *
 * @param titleId   título (nulo para linhas da negociação como um todo)
 * @param component componente afetado
 * @param rule      regra aplicada (ver {@link Rule})
 * @param base      base de cálculo em reais (nula quando não se aplica)
 * @param rate      taxa ou percentual aplicado (% — nulo quando não se aplica)
 * @param days      dias considerados (nulo quando não se aplica)
 * @param factor    fator multiplicativo com 10 casas (nulo quando não se aplica)
 * @param amount    valor resultante, arredondado HALF_EVEN em centavos
 * @param note      observação legível (sem dados pessoais)
 */
public record CalculationLine(UUID titleId, DebtComponent component, Rule rule, BigDecimal base, BigDecimal rate,
                              Long days, BigDecimal factor, Money amount, String note) {

    public enum Rule {
        /** Saldo de principal em aberto no razão. */
        PRINCIPAL_BALANCE,
        /** Correção pelo índice: base × (fator − 1). */
        INDEX_CORRECTION,
        /** Juros simples pro rata die: base × taxa × dias / 30. */
        INTEREST_SIMPLE,
        /** Juros compostos pro rata die: base × ((1 + taxa)^(dias/30) − 1). */
        INTEREST_COMPOUND,
        /** Multa: base × percentual. */
        FINE,
        /** Encargo de valor fixo. */
        FEE_FIXED,
        /** Encargo percentual sobre o principal. */
        FEE_PERCENT,
        /** Encargo já reconhecido no razão do título, em aberto. */
        RECOGNIZED_CHARGE,
        /** Honorários: base × percentual. */
        HONORARIUM,
        /** Desconto concedido numa oferta. */
        DISCOUNT
    }
}
