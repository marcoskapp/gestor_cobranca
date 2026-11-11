package br.com.gestorcobranca.analytics.domain.service;

import br.com.gestorcobranca.analytics.domain.model.PolicySegment;
import br.com.gestorcobranca.analytics.domain.model.PolicySuggestion;
import br.com.gestorcobranca.analytics.domain.model.PolicySuggestion.Kind;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Regras explicáveis de sugestão de revisão da política (decisão 1087). Cada regra exige amostra mínima, para não reagir
 * a ruído, e compara o recorte com o resto do credor (os demais recortes) quando há amostra madura para isso; sem ela,
 * vale só o limite absoluto. O critério de sucesso é o acordo
 * que o devedor cumpre (quebra precoce e pago sobre acordado), não o número de acordos.
 */
public final class PolicyHealthRules {

    /** Acordos no recorte para sugerir sobre desconto. */
    public static final int MIN_AGREEMENTS = 20;
    /** Acordos maduros (60 dias) no recorte para sugerir sobre quebra. */
    public static final int MIN_MATURE = 10;
    /** Consultas do portal no recorte para sugerir sobre o portal. */
    public static final int MIN_PORTAL = 20;
    /** Pedidos "nenhuma condição cabe" no recorte. */
    public static final int MIN_CANNOT_AFFORD = 10;

    private static final BigDecimal HIGH_BREAK = new BigDecimal("0.25");
    private static final BigDecimal BREAK_FACTOR = new BigDecimal("1.5");
    private static final BigDecimal HIGH_ENVELOPE_SHARE = new BigDecimal("0.60");
    private static final BigDecimal LOW_ENVELOPE_AVERAGE = new BigDecimal("30");
    private static final BigDecimal LOW_HIGH_ENVELOPE_SHARE = new BigDecimal("0.10");
    private static final BigDecimal PORTAL_WITHOUT_OFFER_SHARE = new BigDecimal("0.40");
    private static final BigDecimal CANNOT_AFFORD_SHARE = new BigDecimal("0.20");

    private PolicyHealthRules() {
    }

    /** Sugestões dos recortes, na ordem: quebra, portal, orçamento, desconto. */
    public static List<PolicySuggestion> suggestions(List<PolicySegment> segments, PolicySegment total) {
        // o pedido "não cabe" não tem faixa: compara com as consultas da carteira em todas as faixas
        Map<UUID, Long> simulationsByPortfolio = new HashMap<>();
        segments.forEach(s -> simulationsByPortfolio.merge(s.portfolioId(), s.portalSimulations(), Long::sum));
        List<PolicySuggestion> out = new ArrayList<>();
        for (PolicySegment s : segments) {
            PolicySegment rest = total.minus(s);
            BigDecimal reference = rest.matureAgreements() >= MIN_MATURE ? rest.earlyBreakRate() : null;
            if (highEarlyBreak(s, reference)) {
                out.add(new PolicySuggestion(Kind.HIGH_EARLY_BREAK, s.portfolioId(), s.delayBucket()));
            }
            if (s.portalSimulations() >= MIN_PORTAL && s.portalWithoutOfferShare().compareTo(PORTAL_WITHOUT_OFFER_SHARE) >= 0) {
                out.add(new PolicySuggestion(Kind.PORTAL_WITHOUT_OFFER, s.portfolioId(), s.delayBucket()));
            }
            long simulations = s.delayBucket() == null
                    ? simulationsByPortfolio.getOrDefault(s.portfolioId(), 0L) : s.portalSimulations();
            if (cannotAfford(s, simulations)) {
                out.add(new PolicySuggestion(Kind.CANNOT_AFFORD, s.portfolioId(), s.delayBucket()));
            }
            if (discountAtLimit(s, reference)) {
                out.add(new PolicySuggestion(Kind.DISCOUNT_AT_LIMIT, s.portfolioId(), s.delayBucket()));
            }
            if (discountUnused(s, reference)) {
                out.add(new PolicySuggestion(Kind.DISCOUNT_UNUSED, s.portfolioId(), s.delayBucket()));
            }
        }
        return List.copyOf(out);
    }

    static boolean highEarlyBreak(PolicySegment s, BigDecimal reference) {
        if (s.matureAgreements() < MIN_MATURE) {
            return false;
        }
        BigDecimal rate = s.earlyBreakRate();
        return rate.compareTo(HIGH_BREAK) >= 0 && (reference == null || rate.compareTo(reference.multiply(BREAK_FACTOR)) >= 0);
    }

    /** Desconto no limite e adimplência não pior que a do credor (sem maduros suficientes, não sugere ampliar). */
    static boolean discountAtLimit(PolicySegment s, BigDecimal reference) {
        if (s.agreements() < MIN_AGREEMENTS || s.envelopeUsageCount() < MIN_AGREEMENTS || s.matureAgreements() < MIN_MATURE
                || reference == null) {
            return false;
        }
        return s.highEnvelopeShare().compareTo(HIGH_ENVELOPE_SHARE) >= 0 && s.earlyBreakRate().compareTo(reference) <= 0;
    }

    /** Desconto máximo quase nunca usado, sem quebra acima da do credor. */
    static boolean discountUnused(PolicySegment s, BigDecimal reference) {
        if (s.agreements() < MIN_AGREEMENTS || s.envelopeUsageCount() < MIN_AGREEMENTS) {
            return false;
        }
        boolean breakOk = reference == null || s.matureAgreements() < MIN_MATURE || s.earlyBreakRate().compareTo(reference) <= 0;
        return breakOk && s.averageEnvelopeUsage().compareTo(LOW_ENVELOPE_AVERAGE) <= 0
                && s.highEnvelopeShare().compareTo(LOW_HIGH_ENVELOPE_SHARE) <= 0;
    }

    /** Ao menos 10 pedidos e, havendo consultas na carteira, ao menos 20% delas. */
    static boolean cannotAfford(PolicySegment s, long portfolioSimulations) {
        if (s.cannotAfford() < MIN_CANNOT_AFFORD) {
            return false;
        }
        return portfolioSimulations == 0 || BigDecimal.valueOf(s.cannotAfford())
                .compareTo(BigDecimal.valueOf(portfolioSimulations).multiply(CANNOT_AFFORD_SHARE)) >= 0;
    }
}
