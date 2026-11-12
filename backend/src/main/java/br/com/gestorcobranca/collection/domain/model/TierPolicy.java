package br.com.gestorcobranca.collection.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;

import java.util.EnumMap;
import java.util.Map;

/**
 * Parâmetros da distribuição por nível de desempenho da assessoria (distribuicao-por-desempenho.md §4.2 e §10-Q2).
 *
 * @param capacityPercent  multiplicador de capacidade por nível, em % de {@code max_cases_per_user} (50–200)
 * @param developmentShare % dos casos de alto potencial entregues por rodízio a todos os níveis (0–50)
 * @param defaultTier      nível de quem ainda não tem nível publicado (operador novo, sem dados)
 */
public record TierPolicy(Map<PerformanceTier, Integer> capacityPercent, int developmentShare,
                         PerformanceTier defaultTier) {

    public static final int MIN_CAPACITY = 50;
    public static final int MAX_CAPACITY = 200;
    public static final int MAX_DEVELOPMENT_SHARE = 50;

    /** Padrão aprovado pelo responsável em 12/11/2026: A 120%, B 110%, C 100%, D 80%; 15% de desenvolvimento; B. */
    public static final TierPolicy DEFAULT = new TierPolicy(
            Map.of(PerformanceTier.A, 120, PerformanceTier.B, 110, PerformanceTier.C, 100, PerformanceTier.D, 80),
            15, PerformanceTier.B);

    public TierPolicy {
        Guard.require(capacityPercent != null, "settings.tier_capacity_invalid", "Capacidade por nível obrigatória");
        EnumMap<PerformanceTier, Integer> copy = new EnumMap<>(PerformanceTier.class);
        for (PerformanceTier tier : PerformanceTier.values()) {
            Integer value = capacityPercent.get(tier);
            Guard.require(value != null && value >= MIN_CAPACITY && value <= MAX_CAPACITY,
                    "settings.tier_capacity_invalid",
                    "Capacidade do nível %s entre %d%% e %d%%".formatted(tier, MIN_CAPACITY, MAX_CAPACITY));
            copy.put(tier, value);
        }
        capacityPercent = Map.copyOf(copy);
        Guard.require(developmentShare >= 0 && developmentShare <= MAX_DEVELOPMENT_SHARE,
                "settings.tier_development_share_invalid",
                "Fatia de desenvolvimento entre 0% e " + MAX_DEVELOPMENT_SHARE + "%");
        Guard.require(defaultTier != null, "settings.tier_default_invalid", "Nível padrão obrigatório");
    }

    /** Nível efetivo: o publicado ou, sem nível, o padrão. */
    public PerformanceTier effective(PerformanceTier tier) {
        return tier == null ? defaultTier : tier;
    }

    /**
     * Capacidade efetiva = capacidade da fila × multiplicador do nível, arredondada para baixo, no mínimo 1.
     *
     * @param base capacidade por operador da fila (nulo = ilimitada, continua ilimitada)
     */
    public Integer effectiveCapacity(Integer base, PerformanceTier tier) {
        if (base == null) {
            return null;
        }
        long scaled = (long) base * capacityPercent.get(effective(tier)) / 100;
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, scaled));
    }
}
