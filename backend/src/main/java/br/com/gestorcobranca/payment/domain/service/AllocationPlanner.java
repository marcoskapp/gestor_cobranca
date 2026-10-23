package br.com.gestorcobranca.payment.domain.service;

import br.com.gestorcobranca.payment.domain.model.TitleStrategy;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Regras puras de alocação de um valor (RB06), sem efeito colateral:
 * <ul>
 *   <li><b>Acordo</b>: parcelas indicadas primeiro (na ordem informada) e, o excedente, às seguintes
 *       por número — a mesma regra que o negotiation aplica na baixa —, cada uma até o saldo em aberto
 *       (já descontadas alocações em trânsito). O que exceder o saldo do acordo é sobra.</li>
 *   <li><b>Títulos avulsos</b>: até o saldo de cada título; sem valor por título, vencidos primeiro
 *       ({@link TitleStrategy#OLDEST_FIRST}, CC art. 355) ou proporcional ao saldo
 *       ({@link TitleStrategy#PROPORTIONAL}); o excedente é sobra.</li>
 *   <li><b>Componentes do título</b>: proporcional ao saldo de cada componente (maiores restos), o mesmo
 *       critério neutro que o negotiation usa para acordos.</li>
 * </ul>
 */
public final class AllocationPlanner {

    private AllocationPlanner() {
    }

    /** Parcela com saldo em aberto disponível para alocação. */
    public record InstallmentBalance(UUID installmentId, int number, Money remaining) {
    }

    /** Título com saldo em aberto. */
    public record TitleBalance(UUID titleId, LocalDate dueDate, Money remaining) {
    }

    public record Share(UUID targetId, Money amount) {
    }

    /** @param leftover parte do valor que não coube no destino (sobra) */
    public record Plan(List<Share> shares, Money leftover) {

        public Money allocated() {
            return shares.stream().map(Share::amount).reduce(Money.ZERO, Money::plus);
        }
    }

    public static Plan planAgreement(Money amount, List<InstallmentBalance> installments, List<UUID> preferred) {
        requirePositive(amount);
        List<InstallmentBalance> ordered = new ArrayList<>();
        List<UUID> wanted = preferred == null ? List.of() : preferred;
        for (UUID id : wanted) {
            installments.stream().filter(i -> i.installmentId().equals(id)).findFirst()
                    .filter(i -> !ordered.contains(i)).ifPresent(ordered::add);
        }
        installments.stream().filter(i -> !ordered.contains(i))
                .sorted(Comparator.comparingInt(InstallmentBalance::number)).forEach(ordered::add);
        return sequential(amount, ordered.stream().map(i -> new Share(i.installmentId(), i.remaining())).toList());
    }

    public static Plan planTitles(Money amount, List<TitleBalance> titles, TitleStrategy strategy) {
        requirePositive(amount);
        TitleStrategy effective = strategy == null ? TitleStrategy.OLDEST_FIRST : strategy;
        if (effective == TitleStrategy.OLDEST_FIRST) {
            List<TitleBalance> ordered = titles.stream()
                    .sorted(Comparator.comparing(TitleBalance::dueDate, Comparator.nullsLast(Comparator.naturalOrder())))
                    .toList();
            return sequential(amount, ordered.stream().map(t -> new Share(t.titleId(), t.remaining())).toList());
        }
        Money total = titles.stream().map(TitleBalance::remaining).map(m -> m.max(Money.ZERO)).reduce(Money.ZERO, Money::plus);
        if (!amount.isLessThan(total)) {
            List<Share> full = titles.stream().filter(t -> t.remaining().isPositive())
                    .map(t -> new Share(t.titleId(), t.remaining())).toList();
            return new Plan(full, amount.minus(total));
        }
        List<Money> parts = LargestRemainder.split(amount, titles.stream().map(TitleBalance::remaining).toList());
        List<Share> shares = new ArrayList<>();
        for (int i = 0; i < titles.size(); i++) {
            if (parts.get(i).isPositive()) {
                shares.add(new Share(titles.get(i).titleId(), parts.get(i)));
            }
        }
        return new Plan(shares, Money.ZERO);
    }

    /**
     * Distribui o valor entre os componentes do título proporcionalmente ao saldo de cada um.
     *
     * @throws br.com.gestorcobranca.shared.domain.BusinessRuleException valor acima do saldo do título
     */
    public static <C extends Enum<C>> Map<C, Money> splitComponents(Money amount, Map<C, Money> balances) {
        requirePositive(amount);
        Map<C, Money> positive = new LinkedHashMap<>();
        balances.forEach((k, v) -> {
            if (v != null && v.isPositive()) {
                positive.put(k, v);
            }
        });
        Money total = positive.values().stream().reduce(Money.ZERO, Money::plus);
        Guard.require(!amount.isGreaterThan(total), "allocation.exceeds_title_balance", "Valor acima do saldo do título");
        List<C> keys = new ArrayList<>(positive.keySet());
        List<Money> parts = LargestRemainder.split(amount, keys.stream().map(positive::get).toList());
        Map<C, Money> result = keys.isEmpty() ? new LinkedHashMap<>() : new EnumMap<>(keys.getFirst().getDeclaringClass());
        for (int i = 0; i < keys.size(); i++) {
            if (parts.get(i).isPositive()) {
                result.put(keys.get(i), parts.get(i));
            }
        }
        return result;
    }

    private static Plan sequential(Money amount, List<Share> capacities) {
        Money left = amount;
        List<Share> shares = new ArrayList<>();
        for (Share capacity : capacities) {
            if (!left.isPositive()) {
                break;
            }
            Money room = capacity.amount().max(Money.ZERO);
            Money take = left.min(room);
            if (take.isPositive()) {
                shares.add(new Share(capacity.targetId(), take));
                left = left.minus(take);
            }
        }
        return new Plan(shares, left);
    }

    private static void requirePositive(Money amount) {
        Guard.require(amount != null && amount.isPositive(), "allocation.amount_invalid", "Valor a alocar deve ser positivo");
    }
}
