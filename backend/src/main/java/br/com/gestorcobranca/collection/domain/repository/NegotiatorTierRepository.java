package br.com.gestorcobranca.collection.domain.repository;

import br.com.gestorcobranca.collection.domain.model.NegotiatorTier;
import br.com.gestorcobranca.collection.domain.model.PerformanceTier;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Projeção dos níveis de desempenho publicados (ver {@link NegotiatorTier}). */
public interface NegotiatorTierRepository {

    NegotiatorTier save(NegotiatorTier tier);

    boolean existsByUserIdAndPublicationId(UUID userId, UUID publicationId);

    List<NegotiatorTier> findByUserIdInAndValidFromLessThanEqual(Collection<UUID> userIds, LocalDate date);

    /**
     * Nível corrente de cada pessoa na data: a publicação mais recente vigente (maior {@code validFrom}, depois a
     * publicada por último). Pessoa sem publicação, ou cuja última publicação é "sem nível", fica fora do mapa.
     */
    default Map<UUID, PerformanceTier> currentTiers(Collection<UUID> userIds, LocalDate date) {
        Map<UUID, NegotiatorTier> latest = new HashMap<>();
        if (userIds.isEmpty()) {
            return Map.of();
        }
        Comparator<NegotiatorTier> order = Comparator.comparing(NegotiatorTier::getValidFrom)
                .thenComparing(NegotiatorTier::getPublishedAt);
        for (NegotiatorTier t : findByUserIdInAndValidFromLessThanEqual(userIds, date)) {
            latest.merge(t.getUserId(), t, (a, b) -> order.compare(a, b) >= 0 ? a : b);
        }
        Map<UUID, PerformanceTier> tiers = new HashMap<>();
        latest.forEach((user, t) -> {
            if (t.getTier() != null) {
                tiers.put(user, t.getTier());
            }
        });
        return tiers;
    }
}
