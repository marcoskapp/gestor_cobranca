package br.com.gestorcobranca.communication.application.port.out;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Contagem agregada das conversas abertas por aba da caixa de entrada, numa única consulta. */
public interface InboxCounter {

    /**
     * @param allowedCreditorIds credores do escopo (nulo = todos; conversas sem credor só entram sem restrição)
     * @param userId             operador corrente (aba "minhas")
     * @param channel            canal (nulo = todos)
     * @param awaitingOnly       somente conversas aguardando resposta
     * @param breachedBefore     por canal, espera iniciada até este instante = limite estourado
     */
    Counts count(Set<UUID> allowedCreditorIds, UUID userId, String channel, boolean awaitingOnly,
                 Map<String, Instant> breachedBefore);

    record Counts(long mine, long queue, long assistant, long all, long breached) {
    }
}
