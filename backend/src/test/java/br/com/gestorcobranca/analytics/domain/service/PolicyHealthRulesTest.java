package br.com.gestorcobranca.analytics.domain.service;

import br.com.gestorcobranca.analytics.domain.model.PolicySegment;
import br.com.gestorcobranca.analytics.domain.model.PolicySuggestion;
import br.com.gestorcobranca.analytics.domain.model.PolicySuggestion.Kind;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Regras de sugestão da saúde da política (decisão 1087): amostra mínima, comparação com o credor, explicáveis. */
class PolicyHealthRulesTest {

    private static final UUID PORTFOLIO = UUID.randomUUID();

    /** Recorte com acordos: envelope médio e fatia ≥ 90% controlados por {@code avgUse} e {@code high}. */
    private static PolicySegment agreements(String bucket, long n, long mature, long broken, String avgUse, long high) {
        return new PolicySegment(PORTFOLIO, bucket, n, mature, broken, new BigDecimal("1000").multiply(BigDecimal.valueOf(n)),
                new BigDecimal("800").multiply(BigDecimal.valueOf(n)), new BigDecimal(avgUse).multiply(BigDecimal.valueOf(n)),
                n, high, 0, 0, 0);
    }

    private static PolicySegment portal(String bucket, long simulations, long withoutOffer, long cannotAfford) {
        return new PolicySegment(PORTFOLIO, bucket, 0, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, 0,
                simulations, withoutOffer, cannotAfford);
    }

    private static List<Kind> kinds(List<PolicySegment> segments) {
        PolicySegment total = segments.stream().reduce(PolicySegment.empty(null, null), PolicySegment::plus);
        return PolicyHealthRules.suggestions(segments, total).stream().map(PolicySuggestion::kind).toList();
    }

    @Test
    void highEarlyBreakNeedsMatureSampleAndToBeWellAboveTheCreditor() {
        PolicySegment healthy = agreements("D31_90", 100, 80, 8, "50", 10);   // 10%
        PolicySegment risky = agreements("D361_PLUS", 30, 20, 8, "50", 3);   // 40%
        assertThat(kinds(List.of(healthy, risky))).containsExactly(Kind.HIGH_EARLY_BREAK);
        // credor com um recorte só: sem base de comparação, vale o limite absoluto (25%)
        assertThat(kinds(List.of(risky))).containsExactly(Kind.HIGH_EARLY_BREAK);
        // poucos maduros: não reage a ruído
        assertThat(kinds(List.of(healthy, agreements("D361_PLUS", 30, 5, 4, "50", 3)))).isEmpty();
    }

    @Test
    void discountAtLimitOnlyWithGoodPaymentAndDiscountUnusedOnlyWithoutWorseBreak() {
        PolicySegment base = agreements("D31_90", 100, 80, 16, "50", 10);      // 20% de quebra no credor ~
        PolicySegment atLimit = agreements("D181_360", 40, 30, 3, "95", 30);   // 75% no limite, 10% de quebra
        PolicySegment unused = agreements("D0_30", 40, 30, 3, "15", 2);        // média 15%, 5% no limite
        assertThat(kinds(List.of(base, atLimit, unused))).containsExactlyInAnyOrder(Kind.DISCOUNT_AT_LIMIT,
                Kind.DISCOUNT_UNUSED);
        // desconto no limite com quebra acima da do credor: não sugere ampliar
        assertThat(kinds(List.of(base, agreements("D181_360", 40, 30, 12, "95", 30)))).doesNotContain(Kind.DISCOUNT_AT_LIMIT);
    }

    @Test
    void portalSignalsNeedMinimumSample() {
        assertThat(kinds(List.of(portal("D361_PLUS", 50, 25, 0)))).containsExactly(Kind.PORTAL_WITHOUT_OFFER);
        assertThat(kinds(List.of(portal("D361_PLUS", 10, 10, 0)))).isEmpty();
        assertThat(kinds(List.of(portal(null, 0, 0, 12)))).containsExactly(Kind.CANNOT_AFFORD);
        assertThat(kinds(List.of(portal(null, 0, 0, 5)))).isEmpty();
        // "não cabe" comparado com as consultas da carteira (em todas as faixas): 12 de 500 não sugere; 12 de 40 sugere
        assertThat(kinds(List.of(portal("D31_90", 500, 10, 0), portal(null, 0, 0, 12)))).doesNotContain(Kind.CANNOT_AFFORD);
        assertThat(kinds(List.of(portal("D31_90", 40, 4, 0), portal(null, 0, 0, 12)))).contains(Kind.CANNOT_AFFORD);
    }

    @Test
    void ratesAreNullWithoutDenominator() {
        PolicySegment empty = PolicySegment.empty(PORTFOLIO, "D0_30");
        assertThat(empty.earlyBreakRate()).isNull();
        assertThat(empty.paidOverAgreed()).isNull();
        assertThat(empty.averageEnvelopeUsage()).isNull();
        assertThat(empty.portalWithoutOfferShare()).isNull();
        assertThat(agreements("D0_30", 10, 10, 2, "40", 1).earlyBreakRate()).isEqualByComparingTo("0.2");
    }
}
