package br.com.gestorcobranca.shared.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Valor monetário em reais (BRL), escala fixa de 2 casas e arredondamento bancário (HALF_EVEN).
 * Imutável. Nunca use {@code double} para dinheiro neste sistema.
 * <p>
 * Em entidades JPA, declare o campo como {@code Money} com
 * {@code @Column(name = "...", precision = 19, scale = 2)}: o {@link MoneyConverter} é aplicado
 * automaticamente. Em DTOs de API, exponha {@code BigDecimal}.
 */
public record Money(BigDecimal amount) implements Comparable<Money> {

    public static final int SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;
    public static final Money ZERO = new Money(BigDecimal.ZERO);

    public Money {
        Objects.requireNonNull(amount, "amount");
        amount = amount.setScale(SCALE, ROUNDING);
    }

    public static Money of(BigDecimal amount) {
        return new Money(amount);
    }

    public static Money of(String amount) {
        return new Money(new BigDecimal(amount));
    }

    public static Money of(long reais) {
        return new Money(BigDecimal.valueOf(reais));
    }

    public static Money ofNullable(BigDecimal amount) {
        return amount == null ? ZERO : new Money(amount);
    }

    public static Money ofCents(long cents) {
        return new Money(BigDecimal.valueOf(cents, SCALE));
    }

    public long toCents() {
        return amount.movePointRight(SCALE).longValueExact();
    }

    public Money plus(Money other) {
        return new Money(amount.add(other.amount));
    }

    public Money minus(Money other) {
        return new Money(amount.subtract(other.amount));
    }

    public Money times(BigDecimal factor) {
        return new Money(amount.multiply(factor));
    }

    public Money times(long factor) {
        return new Money(amount.multiply(BigDecimal.valueOf(factor)));
    }

    /** Aplica um percentual: R$ 200,00 × 10% = R$ 20,00. */
    public Money percent(Percentage percentage) {
        return new Money(amount.multiply(percentage.asFraction()));
    }

    /** Percentual que este valor representa sobre {@code base} (ex.: desconto concedido). */
    public Percentage ratioOf(Money base) {
        if (base.isZero()) {
            return Percentage.ZERO;
        }
        return Percentage.of(amount.multiply(BigDecimal.valueOf(100)).divide(base.amount, Percentage.SCALE, ROUNDING).abs());
    }

    public Money negate() {
        return new Money(amount.negate());
    }

    public Money abs() {
        return new Money(amount.abs());
    }

    public Money min(Money other) {
        return compareTo(other) <= 0 ? this : other;
    }

    public Money max(Money other) {
        return compareTo(other) >= 0 ? this : other;
    }

    /**
     * Divide em {@code parts} parcelas, distribuindo os centavos residuais nas primeiras
     * parcelas para que a soma seja exatamente igual ao valor original.
     */
    public Money[] split(int parts) {
        if (parts <= 0) {
            throw new IllegalArgumentException("parts deve ser positivo");
        }
        long cents = toCents();
        long base = cents / parts;
        long remainder = Math.abs(cents % parts);
        long signal = Long.signum(cents);
        Money[] result = new Money[parts];
        for (int i = 0; i < parts; i++) {
            long value = base + (i < remainder ? signal : 0);
            result[i] = ofCents(value);
        }
        return result;
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isGreaterThan(Money other) {
        return compareTo(other) > 0;
    }

    public boolean isGreaterThanOrEqual(Money other) {
        return compareTo(other) >= 0;
    }

    public boolean isLessThan(Money other) {
        return compareTo(other) < 0;
    }

    @Override
    public int compareTo(Money other) {
        return amount.compareTo(other.amount);
    }

    @Override
    public String toString() {
        return "BRL " + amount.toPlainString();
    }
}
