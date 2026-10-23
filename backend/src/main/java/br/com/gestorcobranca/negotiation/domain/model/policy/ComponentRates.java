package br.com.gestorcobranca.negotiation.domain.model.policy;

import br.com.gestorcobranca.negotiation.domain.model.calculation.DebtComponent;
import br.com.gestorcobranca.shared.domain.Guard;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Percentual por componente da dívida, de 0 a 100 (ex.: desconto máximo, fatia do envelope).
 * Nulos valem zero. Escala de 4 casas.
 */
public record ComponentRates(BigDecimal principal, BigDecimal correction, BigDecimal interest, BigDecimal fine,
                             BigDecimal fees, BigDecimal honorarium) {

    public static final int SCALE = 4;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public static final ComponentRates NONE = uniform(BigDecimal.ZERO);
    public static final ComponentRates FULL = uniform(HUNDRED);

    public ComponentRates {
        principal = normalize(principal);
        correction = normalize(correction);
        interest = normalize(interest);
        fine = normalize(fine);
        fees = normalize(fees);
        honorarium = normalize(honorarium);
    }

    public static ComponentRates uniform(BigDecimal value) {
        return new ComponentRates(value, value, value, value, value, value);
    }

    private static BigDecimal normalize(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(SCALE, RoundingMode.HALF_EVEN);
    }

    public BigDecimal get(DebtComponent component) {
        return switch (component) {
            case PRINCIPAL -> principal;
            case CORRECTION -> correction;
            case INTEREST -> interest;
            case FINE -> fine;
            case FEES -> fees;
            case HONORARIUM -> honorarium;
        };
    }

    /** Valida que todos os valores estão entre 0 e 100. */
    public ComponentRates validate(String code, String what) {
        for (DebtComponent c : DebtComponent.values()) {
            BigDecimal v = get(c);
            Guard.require(v.signum() >= 0 && v.compareTo(HUNDRED) <= 0, code,
                    what + ": percentual de " + c.name() + " deve estar entre 0 e 100");
        }
        return this;
    }

    /**
     * Aplica fatias percentuais componente a componente: {@code this × shares / 100}, truncado
     * na 4ª casa ({@link RoundingMode#DOWN}) para que o limite calculado nunca exceda o permitido.
     */
    public ComponentRates scaledBy(ComponentRates shares) {
        return new ComponentRates(scale(principal, shares.principal), scale(correction, shares.correction),
                scale(interest, shares.interest), scale(fine, shares.fine), scale(fees, shares.fees),
                scale(honorarium, shares.honorarium));
    }

    /** Aplica a mesma fatia a todos os componentes ({@code this × share / 100}, truncado). */
    public ComponentRates scaledBy(BigDecimal share) {
        return scaledBy(uniform(share));
    }

    public ComponentRates min(ComponentRates other) {
        return new ComponentRates(principal.min(other.principal), correction.min(other.correction),
                interest.min(other.interest), fine.min(other.fine), fees.min(other.fees),
                honorarium.min(other.honorarium));
    }

    /** Verdadeiro se algum componente de {@code this} for maior que o de {@code limit}. */
    public boolean exceedsAny(ComponentRates limit) {
        for (DebtComponent c : DebtComponent.values()) {
            if (get(c).compareTo(limit.get(c)) > 0) {
                return true;
            }
        }
        return false;
    }

    /** Verdadeiro se todos os componentes forem menores ou iguais aos de {@code other}. */
    public boolean notAbove(ComponentRates other) {
        return !exceedsAny(other);
    }

    public boolean allZero() {
        for (DebtComponent c : DebtComponent.values()) {
            if (get(c).signum() != 0) {
                return false;
            }
        }
        return true;
    }

    private static BigDecimal scale(BigDecimal value, BigDecimal share) {
        return value.multiply(share).divide(HUNDRED, SCALE, RoundingMode.DOWN);
    }
}
