package br.com.gestorcobranca.collection.application.port.out;

import br.com.gestorcobranca.collection.domain.model.CaseOrdering;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Seleção concorrente de casos para trabalho ("próximo caso" e distribuição em lote). As linhas
 * escolhidas ficam bloqueadas ({@code FOR UPDATE SKIP LOCKED}) até o fim da transação corrente:
 * dois operadores pedindo o próximo caso ao mesmo tempo nunca recebem o mesmo caso, e nenhum
 * espera pelo outro.
 * <p>
 * Disponível = na fila, ativo e trabalhável (OPEN, IN_NEGOTIATION, PROMISE), elegível, sem
 * operador, com dono {@code QUEUE} (não está com o assistente) e sem próxima ação futura. Casos
 * com retorno da fila já vencido vêm primeiro; depois a ordem da fila.
 *
 * @see CaseOrdering
 */
public interface CaseSelector {

    /**
     * @param allowedCreditorIds credores que o operador pode atender; nulo = todos
     */
    Optional<UUID> lockNextAvailable(UUID queueId, CaseOrdering ordering, Collection<UUID> allowedCreditorIds, Instant now);

    /** Até {@code limit} casos disponíveis da fila, na ordem de entrega, bloqueados para distribuição. */
    List<Candidate> lockAvailable(UUID queueId, CaseOrdering ordering, Instant now, int limit);

    /**
     * Caso do próprio operador com retorno vencido (próxima ação alcançada), o mais antigo primeiro,
     * bloqueado. Tem precedência sobre casos novos da fila.
     */
    Optional<UUID> lockOwnDue(UUID userId, Collection<UUID> allowedCreditorIds, Instant now);

    record Candidate(UUID caseId, UUID creditorId) {
    }
}
