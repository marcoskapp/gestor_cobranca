package br.com.gestorcobranca.negotiation.domain.model.calculation;

import br.com.gestorcobranca.shared.domain.Money;

import java.util.Objects;

/** Valores por componente da dívida (todos em reais, escala 2). Imutável. */
public record Breakdown(Money principal, Money correction, Money interest, Money fine, Money fees, Money honorarium) {

    public static final Breakdown ZERO = new Breakdown(Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO,
            Money.ZERO);

    public Breakdown {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(correction, "correction");
        Objects.requireNonNull(interest, "interest");
        Objects.requireNonNull(fine, "fine");
        Objects.requireNonNull(fees, "fees");
        Objects.requireNonNull(honorarium, "honorarium");
    }

    public Money get(DebtComponent component) {
        return switch (component) {
            case PRINCIPAL -> principal;
            case CORRECTION -> correction;
            case INTEREST -> interest;
            case FINE -> fine;
            case FEES -> fees;
            case HONORARIUM -> honorarium;
        };
    }

    public Breakdown with(DebtComponent component, Money value) {
        return new Breakdown(
                component == DebtComponent.PRINCIPAL ? value : principal,
                component == DebtComponent.CORRECTION ? value : correction,
                component == DebtComponent.INTEREST ? value : interest,
                component == DebtComponent.FINE ? value : fine,
                component == DebtComponent.FEES ? value : fees,
                component == DebtComponent.HONORARIUM ? value : honorarium);
    }

    public Breakdown plus(Breakdown other) {
        return new Breakdown(principal.plus(other.principal), correction.plus(other.correction),
                interest.plus(other.interest), fine.plus(other.fine), fees.plus(other.fees),
                honorarium.plus(other.honorarium));
    }

    public Breakdown minus(Breakdown other) {
        return new Breakdown(principal.minus(other.principal), correction.minus(other.correction),
                interest.minus(other.interest), fine.minus(other.fine), fees.minus(other.fees),
                honorarium.minus(other.honorarium));
    }

    /** Soma de todos os componentes. */
    public Money total() {
        return principal.plus(correction).plus(interest).plus(fine).plus(fees).plus(honorarium);
    }

    /** Principal + correção + juros + multa + encargos (base "dívida atualizada" dos honorários). */
    public Money totalBeforeHonorarium() {
        return total().minus(honorarium);
    }
}
