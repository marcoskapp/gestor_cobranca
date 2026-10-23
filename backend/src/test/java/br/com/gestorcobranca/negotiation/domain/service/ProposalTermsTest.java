package br.com.gestorcobranca.negotiation.domain.service;

import br.com.gestorcobranca.negotiation.domain.model.authority.AuthorityLimits;
import br.com.gestorcobranca.negotiation.domain.model.policy.ComponentRates;
import br.com.gestorcobranca.shared.domain.Money;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ProposalTermsTest {

    private static final UUID T1 = UUID.randomUUID();
    private static final UUID T2 = UUID.randomUUID();

    private static ComponentRates rates(String interest, String honorarium) {
        return new ComponentRates(BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal(interest), BigDecimal.ZERO,
                BigDecimal.ZERO, new BigDecimal(honorarium));
    }

    @Test
    void fingerprintComparesConditionsNotDatesOrAccruedValues() {
        String suggestion = ProposalTerms.fingerprint(List.of(T1, T2), rates("50", "10"), 6, null);
        assertThat(ProposalTerms.fingerprint(List.of(T2, T1), rates("50.0000", "10.00"), 6, null)).isEqualTo(suggestion);
        assertThat(ProposalTerms.fingerprint(List.of(T1, T2), rates("50", "20"), 6, null)).isNotEqualTo(suggestion);
        assertThat(ProposalTerms.fingerprint(List.of(T1, T2), rates("50", "10"), 4, null)).isNotEqualTo(suggestion);
        assertThat(ProposalTerms.fingerprint(List.of(T1, T2), rates("50", "10"), 6, Money.of("300.00")))
                .isNotEqualTo(suggestion);
        assertThat(ProposalTerms.fingerprint(List.of(T1), rates("50", "10"), 6, null)).isNotEqualTo(suggestion);
    }

    @Test
    void renegotiationCountsOnlyTheIncrementalGain() {
        // acordo de 1.200 com 400 pagos (800 em aberto) renegociado por 1.000 → ganho de 200
        assertThat(ProposalTerms.incrementalAmount(Money.of("1000.00"), Money.of("1200.00"), Money.of("400.00")))
                .isEqualTo(Money.of("200.00"));
        // renegociação por menos que o saldo em aberto não gera ganho (nunca negativo)
        assertThat(ProposalTerms.incrementalAmount(Money.of("700.00"), Money.of("1200.00"), Money.of("400.00")))
                .isEqualTo(Money.ZERO);
        assertThat(ProposalTerms.incrementalAmount(Money.of("700.00"), null, null)).isEqualTo(Money.of("700.00"));
    }

    @Test
    void envelopeUsageAndRequiredShareRouteTheApprovalQueue() {
        assertThat(ProposalTerms.envelopeUsage(Money.of("50.00"), Money.of("200.00"))).isEqualByComparingTo("25");
        assertThat(ProposalTerms.envelopeUsage(Money.of("50.00"), Money.ZERO)).isEqualByComparingTo("0");
        BigDecimal share = ProposalTerms.requiredDiscountShare(rates("40", "40"), rates("100", "50"));
        assertThat(share).isEqualByComparingTo("80");
        assertThat(ProposalTerms.downPaymentShare(Money.of("100.00"), Money.of("1000.00"), 6)).isEqualByComparingTo("10");
        assertThat(ProposalTerms.downPaymentShare(Money.of("1000.00"), Money.of("1000.00"), 1)).isEqualByComparingTo("100");

        var requirement = new ProposalTerms.AuthorityRequirement(2, share, 6, Money.of("1500.00"), new BigDecimal("10"), 0);
        assertThat(requirement.satisfiedBy(limits(99, "100", 24, "200000", "0", true, 2))).isTrue();
        assertThat(requirement.satisfiedBy(limits(99, "70", 24, "200000", "0", true, 2))).isFalse();
        assertThat(requirement.satisfiedBy(limits(1, "100", 24, null, "0", true, 2))).isFalse();
        assertThat(requirement.satisfiedBy(limits(99, "100", 24, null, "0", false, 2))).as("sem canApprove").isFalse();
        assertThat(new ProposalTerms.AuthorityRequirement(0, BigDecimal.ZERO, 1, Money.of("10.00"), new BigDecimal("100"), 3)
                .satisfiedBy(limits(99, "100", 24, null, "0", true, 2))).as("renegociações").isFalse();
    }

    private static AuthorityLimits limits(int level, String share, int installments, String amount, String down,
                                          boolean approve, int renegotiations) {
        return new AuthorityLimits(UUID.randomUUID(), "X", level, new BigDecimal(share), installments,
                amount == null ? null : Money.of(amount), new BigDecimal(down), true, approve, renegotiations);
    }
}
