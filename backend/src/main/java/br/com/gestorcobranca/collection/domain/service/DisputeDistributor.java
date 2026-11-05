package br.com.gestorcobranca.collection.domain.service;

import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Distribuição automática de uma contestação nova (regra pura): o analista elegível com menos contestações abertas;
 * empate, quem recebeu a última atribuição há mais tempo (nunca recebeu = primeiro) e, por fim, o id.
 */
public final class DisputeDistributor {

    private DisputeDistributor() {
    }

    /**
     * @param candidates   analistas elegíveis (ativos, com permissão e acesso ao credor)
     * @param openCounts   contestações abertas por analista (ausente = 0)
     * @param lastAssigned instante da última atribuição de cada analista (ausente = nunca)
     */
    public static Optional<UUID> pick(Collection<UUID> candidates, Map<UUID, Long> openCounts,
                                      Map<UUID, java.time.Instant> lastAssigned) {
        return candidates.stream().min(Comparator
                .comparingLong((UUID id) -> openCounts.getOrDefault(id, 0L))
                .thenComparing(id -> lastAssigned.getOrDefault(id, java.time.Instant.MIN))
                .thenComparing(UUID::toString));
    }
}
