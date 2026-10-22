package br.com.gestorcobranca.debt.domain.service;

import br.com.gestorcobranca.debt.domain.model.LedgerComponent;
import br.com.gestorcobranca.debt.domain.model.LedgerEntryType;
import br.com.gestorcobranca.debt.domain.model.TitleLedgerEntry;
import br.com.gestorcobranca.shared.domain.Money;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Posição do título calculada a partir do razão (RB03): para cada componente (principal, juros,
 * multa, encargos, honorários) separa o que foi lançado, descontado, pago e estornado, e o
 * saldo resultante. O saldo do título é a soma dos saldos dos componentes.
 *
 * @param components    posição por componente (todos os componentes presentes, mesmo zerados)
 * @param openBalance   saldo em aberto (soma de todos os lançamentos)
 * @param paidTotal     pagamentos líquidos de estornos
 * @param lastPaymentAt instante do último pagamento não estornado (nulo se não houver)
 */
public record TitleBalance(Map<LedgerComponent, ComponentBalance> components, Money openBalance, Money paidTotal,
                           Instant lastPaymentAt) {

    /**
     * @param charged    abertura, encargos reconhecidos e ajustes (débitos)
     * @param discounted descontos e baixas concedidos (valor positivo)
     * @param paid       pagamentos brutos (valor positivo)
     * @param reversed   estornos de pagamento (valor positivo)
     * @param balance    {@code charged - discounted - paid + reversed}
     */
    public record ComponentBalance(Money charged, Money discounted, Money paid, Money reversed, Money balance) {

        static final ComponentBalance ZERO = new ComponentBalance(Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO, Money.ZERO);

        ComponentBalance add(LedgerEntryType type, Money amount) {
            Money newBalance = balance.plus(amount);
            return switch (type) {
                case OPENING, CHARGE, ADJUSTMENT -> new ComponentBalance(charged.plus(amount), discounted, paid, reversed, newBalance);
                case DISCOUNT, WRITE_OFF -> new ComponentBalance(charged, discounted.minus(amount), paid, reversed, newBalance);
                case PAYMENT -> new ComponentBalance(charged, discounted, paid.minus(amount), reversed, newBalance);
                case REVERSAL -> new ComponentBalance(charged, discounted, paid, reversed.plus(amount), newBalance);
            };
        }
    }

    public TitleBalance {
        components = Collections.unmodifiableMap(new EnumMap<>(components));
    }

    public static TitleBalance of(Collection<TitleLedgerEntry> entries) {
        Map<LedgerComponent, ComponentBalance> components = new EnumMap<>(LedgerComponent.class);
        for (LedgerComponent component : LedgerComponent.values()) {
            components.put(component, ComponentBalance.ZERO);
        }
        Money open = Money.ZERO;
        Money paid = Money.ZERO;
        Set<UUID> reversedPayments = new HashSet<>();
        for (TitleLedgerEntry entry : entries) {
            components.compute(entry.getComponent(), (c, current) -> current.add(entry.getEntryType(), entry.getAmount()));
            open = open.plus(entry.getAmount());
            if (entry.getEntryType() == LedgerEntryType.PAYMENT) {
                paid = paid.minus(entry.getAmount());
            } else if (entry.getEntryType() == LedgerEntryType.REVERSAL) {
                paid = paid.minus(entry.getAmount());
                reversedPayments.add(entry.getReferenceId());
            }
        }
        Instant lastPayment = null;
        for (TitleLedgerEntry entry : entries) {
            if (entry.getEntryType() == LedgerEntryType.PAYMENT && !reversedPayments.contains(entry.getReferenceId())
                    && (lastPayment == null || entry.getOccurredAt().isAfter(lastPayment))) {
                lastPayment = entry.getOccurredAt();
            }
        }
        return new TitleBalance(components, open, paid, lastPayment);
    }

    public Money balanceOf(LedgerComponent component) {
        return components.get(component).balance();
    }
}
