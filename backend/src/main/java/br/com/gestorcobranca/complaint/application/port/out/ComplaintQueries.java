package br.com.gestorcobranca.complaint.application.port.out;

import br.com.gestorcobranca.complaint.application.port.in.ComplaintUseCases.CreditorIndicator;
import br.com.gestorcobranca.shared.pendingwork.PendingWorkCount;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Consultas agregadas do módulo (indexadas; RLS do tenant corrente). */
public interface ComplaintQueries {

    /**
     * Reclamações aguardando resposta perto do prazo em {@code today} (aviso do canal iniciado) ou vencidas.
     *
     * @param creditorScope credores do ator (nulo = todos)
     * @param assigneeId    só do responsável (nulo = todas)
     * @param unassignedOnly só sem responsável
     */
    PendingWorkCount dueSoon(Set<UUID> creditorScope, UUID assigneeId, boolean unassignedOnly, LocalDate today);

    /** Indicador por credor no período {@code [from, to]} (datas de negócio). */
    List<CreditorIndicator> indicatorByCreditor(Set<UUID> creditorScope, Instant fromInclusive, Instant toExclusive,
                                                LocalDate today);

    /** Reclamações aguardando resposta por responsável. */
    Map<UUID, Long> openByAssignees(Collection<UUID> assigneeIds);

    /** Evidências por reclamação (todas as situações). */
    Map<UUID, Integer> evidenceCounts(Collection<UUID> complaintIds);
}
