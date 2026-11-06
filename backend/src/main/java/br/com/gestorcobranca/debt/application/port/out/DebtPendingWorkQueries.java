package br.com.gestorcobranca.debt.application.port.out;

import br.com.gestorcobranca.shared.pendingwork.PendingWorkCount;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Contagens das pendências do debt; {@code creditorScope} nulo = todos os credores. {@code singleId} = título. */
public interface DebtPendingWorkQueries {

    /** Correções de valor aguardando aprovação, pedidas por outra pessoa. */
    PendingWorkCount amountApprovals(Set<UUID> creditorScope, UUID approverId);

    /**
     * Aumento do limite da correção de valor proposto por outra pessoa depois de {@code proposedAfter} (as anteriores já
     * venceram), aguardando confirmação (0 ou 1).
     */
    PendingWorkCount thresholdProposal(UUID confirmerId, Instant proposedAfter);

    /** Divergências abertas entre o valor corrigido e a carga do credor. */
    PendingWorkCount amountDivergences(Set<UUID> creditorScope);
}
