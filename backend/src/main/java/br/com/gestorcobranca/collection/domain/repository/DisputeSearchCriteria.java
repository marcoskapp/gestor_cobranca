package br.com.gestorcobranca.collection.domain.repository;

import br.com.gestorcobranca.collection.domain.model.DisputeOrigin;
import br.com.gestorcobranca.collection.domain.model.DisputeStatus;
import br.com.gestorcobranca.collection.domain.model.DisputeType;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * Filtros da fila de contestações. Nulos/vazios = sem filtro.
 *
 * @param allowedCreditorIds escopo do ator (nulo = todos)
 * @param assigneeId         responsável
 * @param unassigned         só as sem responsável
 * @param overdue            prazo de resposta ao devedor vencido
 * @param dueUntil           prazo interno ou de resposta até a data (inclusive)
 * @param awaitingCreditor   aguardando retorno do credor
 * @param noticePending      resolvidas com resposta ao devedor pendente
 * @param adjustmentPending  ajuste sugerido no título pendente
 * @param supervisionAsOf    visão do supervisor: sem responsável ou com prazo (interno ou de resposta) antes desta data
 * @param mailReplyPending   com resposta do credor por e-mail aguardando a confirmação do analista
 */
public record DisputeSearchCriteria(Set<UUID> allowedCreditorIds, UUID creditorId, Set<DisputeStatus> statuses,
                                    Set<DisputeType> types, Set<DisputeOrigin> origins, UUID assigneeId,
                                    boolean unassigned, Boolean overdue, LocalDate dueUntil, Boolean awaitingCreditor,
                                    boolean noticePending, boolean adjustmentPending, LocalDate supervisionAsOf,
                                    boolean mailReplyPending) {

    public DisputeSearchCriteria(Set<UUID> allowedCreditorIds, UUID creditorId, Set<DisputeStatus> statuses,
                                 Set<DisputeType> types, Set<DisputeOrigin> origins, UUID assigneeId,
                                 boolean unassigned, Boolean overdue, LocalDate dueUntil, Boolean awaitingCreditor,
                                 boolean noticePending, boolean adjustmentPending, LocalDate supervisionAsOf) {
        this(allowedCreditorIds, creditorId, statuses, types, origins, assigneeId, unassigned, overdue, dueUntil,
                awaitingCreditor, noticePending, adjustmentPending, supervisionAsOf, false);
    }
}
