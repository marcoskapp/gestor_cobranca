package br.com.gestorcobranca.incentive.domain.model.plan;

import br.com.gestorcobranca.incentive.domain.model.DelayBucket;
import br.com.gestorcobranca.incentive.domain.model.Segment;

import java.util.Set;
import java.util.UUID;

/**
 * Seleção de segmentos por credor, carteira e faixa de atraso. Conjunto vazio = qualquer valor
 * naquela dimensão.
 */
public record SegmentSelector(Set<UUID> creditorIds, Set<UUID> portfolioIds, Set<DelayBucket> delayBuckets) {

    public static final SegmentSelector ANY = new SegmentSelector(Set.of(), Set.of(), Set.of());

    public SegmentSelector {
        creditorIds = creditorIds == null ? Set.of() : Set.copyOf(creditorIds);
        portfolioIds = portfolioIds == null ? Set.of() : Set.copyOf(portfolioIds);
        delayBuckets = delayBuckets == null ? Set.of() : Set.copyOf(delayBuckets);
    }

    public static SegmentSelector buckets(DelayBucket... buckets) {
        return new SegmentSelector(Set.of(), Set.of(), Set.of(buckets));
    }

    public boolean matches(Segment segment) {
        return (creditorIds.isEmpty() || creditorIds.contains(segment.creditorId()))
                && (portfolioIds.isEmpty() || (segment.portfolioId() != null && portfolioIds.contains(segment.portfolioId())))
                && (delayBuckets.isEmpty() || delayBuckets.contains(segment.delayBucket()));
    }

    /** Quantidade de dimensões restritas: a seleção mais específica prevalece. */
    public int specificity() {
        return (creditorIds.isEmpty() ? 0 : 1) + (portfolioIds.isEmpty() ? 0 : 1) + (delayBuckets.isEmpty() ? 0 : 1);
    }
}
