package br.com.gestorcobranca.negotiation.domain.service;

import br.com.gestorcobranca.negotiation.domain.model.authority.AuthorityLimits;
import br.com.gestorcobranca.negotiation.domain.model.calculation.DebtComponent;
import br.com.gestorcobranca.negotiation.domain.model.policy.ComponentRates;
import br.com.gestorcobranca.shared.domain.Money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.UUID;

/** Regras puras das condições de uma proposta: impressão digital, uso do envelope, exigência de alçada, renegociação. */
public final class ProposalTerms {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private ProposalTerms() {
    }

    /**
     * Impressão digital das condições comerciais, usada para decidir se o humano apenas confirmou a
     * sugestão do assistente ({@code HANDOFF_CONFIRMED}) ou negociou ({@code HANDOFF_NEGOTIATED}):
     * títulos, desconto por componente, número de pagamentos e entrada pedida ({@code MIN} = a mínima
     * exigida). Datas e valores recalculados (juros até a data-base) não entram: o tempo entre a
     * sugestão e a adoção não é negociação.
     */
    public static String fingerprint(Collection<UUID> titleIds, ComponentRates discounts, int installments,
                                     Money requestedDownPayment) {
        StringBuilder canonical = new StringBuilder("v1|");
        titleIds.stream().map(UUID::toString).sorted().forEach(id -> canonical.append(id).append(','));
        canonical.append('|');
        for (DebtComponent c : DebtComponent.values()) {
            canonical.append(c.name()).append('=').append(discounts.get(c).stripTrailingZeros().toPlainString()).append(';');
        }
        canonical.append('|').append(installments).append('|')
                .append(requestedDownPayment == null ? "MIN" : requestedDownPayment.amount().toPlainString());
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }

    /** Uso do envelope (0–100): desconto concedido ÷ desconto máximo da política para a forma de pagamento. */
    public static BigDecimal envelopeUsage(Money discount, Money maxDiscount) {
        if (!maxDiscount.isPositive()) {
            return BigDecimal.ZERO;
        }
        return discount.amount().multiply(HUNDRED).divide(maxDiscount.amount(), 4, RoundingMode.HALF_EVEN).min(HUNDRED);
    }

    /**
     * Fração do envelope usada pelo componente mais exigente (desconto ÷ envelope), arredondada para
     * cima: um perfil precisa de {@code maxDiscountShare} ao menos igual para conceder as condições.
     */
    public static BigDecimal requiredDiscountShare(ComponentRates discounts, ComponentRates envelope) {
        BigDecimal required = BigDecimal.ZERO;
        for (DebtComponent c : DebtComponent.values()) {
            BigDecimal d = discounts.get(c);
            if (d.signum() == 0) {
                continue;
            }
            BigDecimal e = envelope.get(c);
            BigDecimal share = e.signum() == 0 ? HUNDRED : d.multiply(HUNDRED).divide(e, 4, RoundingMode.CEILING);
            required = required.max(share);
        }
        return required.min(HUNDRED);
    }

    /** Entrada como % do valor negociado (à vista = 100), truncada. */
    public static BigDecimal downPaymentShare(Money downPayment, Money negotiated, int installments) {
        if (installments <= 1 || !negotiated.isPositive()) {
            return HUNDRED;
        }
        return downPayment.amount().multiply(HUNDRED).divide(negotiated.amount(), 4, RoundingMode.DOWN);
    }

    /**
     * Exigência de alçada da proposta, usada para rotear a fila de aprovação a quem pode aprovar
     * (F16). A aprovação revalida tudo pelo {@code NegotiationGuard}; este filtro só evita mostrar
     * a proposta a quem certamente não pode aprová-la.
     */
    public record AuthorityRequirement(int level, BigDecimal discountShare, int installments, Money totalAmount,
                                       BigDecimal downPaymentShare, int renegotiationNumber) {

        public boolean satisfiedBy(AuthorityLimits l) {
            return l.canApprove()
                    && l.maxConcessionLevel() >= level
                    && l.maxDiscountShare().compareTo(discountShare) >= 0
                    && l.maxInstallments() >= installments
                    && (l.maxAgreementAmount() == null || !totalAmount.isGreaterThan(l.maxAgreementAmount()))
                    && (installments <= 1 || l.minDownPaymentShare().compareTo(downPaymentShare) <= 0)
                    && l.maxRenegotiations() >= renegotiationNumber;
        }
    }

    /**
     * Ganho incremental de uma renegociação (RB05): o que o novo acordo acrescenta ao saldo que o
     * acordo substituído ainda tinha a receber — {@code máx(0, novo − (substituído − pago))}. O
     * valor já recebido no acordo anterior nunca é contado de novo como recuperação.
     */
    public static Money incrementalAmount(Money newTotal, Money replacedTotal, Money replacedPaid) {
        if (replacedTotal == null) {
            return newTotal;
        }
        Money outstanding = replacedTotal.minus(replacedPaid == null ? Money.ZERO : replacedPaid).max(Money.ZERO);
        return newTotal.minus(outstanding).max(Money.ZERO);
    }
}
