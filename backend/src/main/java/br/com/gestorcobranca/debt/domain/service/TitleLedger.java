package br.com.gestorcobranca.debt.domain.service;

import br.com.gestorcobranca.debt.domain.model.DebtTitle;
import br.com.gestorcobranca.debt.domain.model.LedgerComponent;
import br.com.gestorcobranca.debt.domain.model.LedgerEntryType;
import br.com.gestorcobranca.debt.domain.model.TitleLedgerEntry;
import br.com.gestorcobranca.debt.domain.model.TitleStatus;
import br.com.gestorcobranca.shared.domain.BusinessRuleException;
import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Regras de lançamento no razão do título. Cada operação recebe o título e <strong>todos</strong>
 * os lançamentos já existentes (o chamador garante exclusividade com lock pessimista do título),
 * gera os novos lançamentos e atualiza o cache de saldo/status do título.
 * <p>
 * Idempotência: uma operação é identificada por (tipo, referência). Repetir a mesma operação
 * com os mesmos valores não gera lançamentos ({@link Posting#applied()} falso); repetir com
 * valores diferentes é conflito.
 * <p>
 * Pagamentos, descontos e baixas não podem superar o saldo do componente: excesso de pagamento
 * é tratado no módulo payment (devolução/crédito), nunca como saldo negativo do título.
 */
public final class TitleLedger {

    private TitleLedger() {
    }

    /**
     * Resultado de uma operação no razão.
     *
     * @param entries        lançamentos novos a persistir (vazio se a operação já havia sido aplicada)
     * @param previousStatus status antes da operação
     * @param previousBalance saldo em aberto antes da operação
     */
    public record Posting(List<TitleLedgerEntry> entries, TitleStatus previousStatus, Money previousBalance) {

        public Posting {
            entries = List.copyOf(entries);
        }

        public boolean applied() {
            return !entries.isEmpty();
        }

        public Money total() {
            return entries.stream().map(TitleLedgerEntry::getAmount).reduce(Money.ZERO, Money::plus);
        }
    }

    /** Lançamento de abertura do principal na criação do título. */
    public static Posting open(DebtTitle title, Instant at, String referenceType, UUID referenceId) {
        return post(title, List.of(), LedgerEntryType.OPENING, Map.of(LedgerComponent.PRINCIPAL, title.getPrincipalAmount()),
                at, referenceType, referenceId, null);
    }

    /** Correção do principal informada pelo credor numa nova carga. */
    public static Posting adjustPrincipal(DebtTitle title, Collection<TitleLedgerEntry> existing, Money newPrincipal,
                                          Instant at, String referenceType, UUID referenceId, String reason) {
        Money delta = newPrincipal.minus(title.getPrincipalAmount());
        if (delta.isZero()) {
            return new Posting(List.of(), title.getStatus(), title.getOpenBalance());
        }
        TitleBalance balance = TitleBalance.of(existing);
        Guard.require(!balance.balanceOf(LedgerComponent.PRINCIPAL).plus(delta).isNegative(), "title.principal_below_settled",
                "Novo principal é menor que o já pago ou descontado");
        title.changePrincipal(newPrincipal);
        return post(title, existing, LedgerEntryType.ADJUSTMENT, Map.of(LedgerComponent.PRINCIPAL, delta),
                at, referenceType, referenceId, reason);
    }

    /**
     * Pagamento alocado por componente.
     *
     * @param parts valores pagos (positivos) por componente
     */
    public static Posting applyPayment(DebtTitle title, Collection<TitleLedgerEntry> existing, UUID paymentId,
                                       Map<LedgerComponent, Money> parts, Instant at) {
        return creditEntries(title, existing, LedgerEntryType.PAYMENT, TitleLedgerEntry.REF_PAYMENT, paymentId, parts,
                at, null);
    }

    /**
     * Estorno: espelha, com sinal invertido, cada lançamento do pagamento original, que permanece
     * no razão (RB06).
     */
    public static Posting reversePayment(DebtTitle title, Collection<TitleLedgerEntry> existing, UUID paymentId,
                                         Instant at, String reason) {
        Guard.require(reason != null && !reason.isBlank(), "ledger.reversal_reason_required", "Informe o motivo do estorno");
        Map<LedgerComponent, Money> paid = amountsOf(existing, LedgerEntryType.PAYMENT, TitleLedgerEntry.REF_PAYMENT, paymentId);
        if (paid.isEmpty()) {
            throw new BusinessRuleException("ledger.payment_not_found", "Pagamento não lançado neste título");
        }
        Map<LedgerComponent, Money> mirrored = new EnumMap<>(LedgerComponent.class);
        paid.forEach((component, amount) -> mirrored.put(component, amount.negate()));
        return post(title, existing, LedgerEntryType.REVERSAL, mirrored, at, TitleLedgerEntry.REF_PAYMENT, paymentId, reason);
    }

    /**
     * Juros, multa, encargos ou honorários reconhecidos (ex.: na formalização de um acordo).
     * O principal só muda por abertura ou ajuste do credor.
     */
    public static Posting applyCharges(DebtTitle title, Collection<TitleLedgerEntry> existing, String referenceType,
                                       UUID referenceId, Map<LedgerComponent, Money> parts, Instant at, String reason) {
        Guard.require(!parts.containsKey(LedgerComponent.PRINCIPAL), "ledger.charge_principal_not_allowed",
                "Encargos não podem ser lançados no principal");
        requirePositiveParts(parts);
        return post(title, existing, LedgerEntryType.CHARGE, parts, at, referenceType, referenceId, reason);
    }

    /**
     * Desconto ou baixa concedidos numa negociação.
     *
     * @param type  {@link LedgerEntryType#DISCOUNT} ou {@link LedgerEntryType#WRITE_OFF}
     * @param parts valores concedidos (positivos) por componente
     */
    public static Posting applyDiscount(DebtTitle title, Collection<TitleLedgerEntry> existing, LedgerEntryType type,
                                        UUID agreementId, Map<LedgerComponent, Money> parts, Instant at, String reason) {
        Guard.require(type == LedgerEntryType.DISCOUNT || type == LedgerEntryType.WRITE_OFF, "ledger.discount_type_invalid",
                "Tipo de concessão inválido");
        Guard.require(reason != null && !reason.isBlank(), "ledger.discount_reason_required", "Informe o motivo da concessão");
        return creditEntries(title, existing, type, TitleLedgerEntry.REF_AGREEMENT, agreementId, parts, at, reason);
    }

    private static Posting creditEntries(DebtTitle title, Collection<TitleLedgerEntry> existing, LedgerEntryType type,
                                         String referenceType, UUID referenceId, Map<LedgerComponent, Money> parts,
                                         Instant at, String reason) {
        requirePositiveParts(parts);
        Map<LedgerComponent, Money> negated = new EnumMap<>(LedgerComponent.class);
        parts.forEach((component, amount) -> negated.put(component, amount.negate()));
        if (!alreadyApplied(existing, type, referenceType, referenceId, negated)) {
            TitleBalance balance = TitleBalance.of(existing);
            parts.forEach((component, amount) -> {
                if (amount.isGreaterThan(balance.balanceOf(component))) {
                    throw new BusinessRuleException("ledger.amount_exceeds_balance",
                            "Valor de %s excede o saldo do componente %s".formatted(type, component),
                            Map.of("component", component.name(), "balance", balance.balanceOf(component).amount().toPlainString(),
                                    "amount", amount.amount().toPlainString()));
                }
            });
        }
        return post(title, existing, type, negated, at, referenceType, referenceId, reason);
    }

    /**
     * Núcleo comum: idempotência, criação dos lançamentos e atualização do título.
     *
     * @param signedParts valores já com o sinal da convenção do razão
     */
    private static Posting post(DebtTitle title, Collection<TitleLedgerEntry> existing, LedgerEntryType type,
                                Map<LedgerComponent, Money> signedParts, Instant at, String referenceType,
                                UUID referenceId, String reason) {
        Guard.require(at != null, "ledger.occurred_at_required", "Data do lançamento obrigatória");
        Guard.require(referenceId != null, "ledger.reference_required", "Referência do lançamento obrigatória");
        Guard.require(!signedParts.isEmpty(), "ledger.parts_required", "Informe ao menos um componente com valor");
        TitleStatus previousStatus = title.getStatus();
        Money previousBalance = title.getOpenBalance();
        if (alreadyApplied(existing, type, referenceType, referenceId, signedParts)) {
            return new Posting(List.of(), previousStatus, previousBalance);
        }
        title.requireAcceptsPosting(type);

        List<TitleLedgerEntry> created = new ArrayList<>();
        new EnumMap<>(signedParts).forEach((component, amount) -> created.add(
                new TitleLedgerEntry(title.getId(), type, component, amount, at, referenceType, referenceId, reason)));
        List<TitleLedgerEntry> all = new ArrayList<>(existing);
        all.addAll(created);
        TitleBalance balance = TitleBalance.of(all);
        title.recordPosting(type, balance.openBalance(), balance.paidTotal(), balance.lastPaymentAt());
        return new Posting(created, previousStatus, previousBalance);
    }

    /**
     * @return true se a operação já consta do razão com os mesmos valores
     * @throws ConflictException se já consta com valores diferentes
     */
    private static boolean alreadyApplied(Collection<TitleLedgerEntry> existing, LedgerEntryType type,
                                          String referenceType, UUID referenceId, Map<LedgerComponent, Money> signedParts) {
        Map<LedgerComponent, Money> recorded = amountsOf(existing, type, referenceType, referenceId);
        if (recorded.isEmpty()) {
            return false;
        }
        if (!recorded.equals(new EnumMap<>(signedParts))) {
            throw new ConflictException("ledger.reference_conflict",
                    "Operação %s já lançada para esta referência com valores diferentes".formatted(type),
                    Map.of("referenceType", referenceType, "referenceId", referenceId.toString()));
        }
        return true;
    }

    private static Map<LedgerComponent, Money> amountsOf(Collection<TitleLedgerEntry> existing, LedgerEntryType type,
                                                         String referenceType, UUID referenceId) {
        Map<LedgerComponent, Money> amounts = new EnumMap<>(LedgerComponent.class);
        existing.stream().filter(e -> e.refersTo(type, referenceType, referenceId))
                .forEach(e -> amounts.merge(e.getComponent(), e.getAmount(), Money::plus));
        return amounts;
    }

    private static void requirePositiveParts(Map<LedgerComponent, Money> parts) {
        Guard.require(parts != null && !parts.isEmpty(), "ledger.parts_required", "Informe ao menos um componente com valor");
        parts.forEach((component, amount) -> Guard.require(amount != null && amount.isPositive(),
                "ledger.part_amount_invalid", "Valor do componente %s deve ser positivo".formatted(component)));
    }
}
