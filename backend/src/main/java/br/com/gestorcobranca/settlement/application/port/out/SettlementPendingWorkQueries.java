package br.com.gestorcobranca.settlement.application.port.out;

import br.com.gestorcobranca.shared.pendingwork.PendingWorkCount;

import java.util.Set;
import java.util.UUID;

/** Contagens de "Minhas pendências" do módulo (consultas indexadas; RLS do tenant corrente). */
public interface SettlementPendingWorkQueries {

    /** Demonstrativos calculados aguardando revisão. @param creditorScope credores permitidos (nulo = todos) */
    PendingWorkCount awaitingReview(Set<UUID> creditorScope);

    /**
     * Demonstrativos revisados que o usuário pode aprovar: não foi ele quem calculou nem pediu ajuste
     * aplicado, e não há ajuste aguardando segunda aprovação.
     */
    PendingWorkCount awaitingApproval(Set<UUID> creditorScope, UUID approverId);

    /**
     * Ajustes aguardando segunda aprovação pedidos por outra pessoa, em demonstrativos em aberto;
     * {@code singleId} é o do demonstrativo.
     */
    PendingWorkCount adjustmentsAwaitingApproval(Set<UUID> creditorScope, UUID approverId);
}
