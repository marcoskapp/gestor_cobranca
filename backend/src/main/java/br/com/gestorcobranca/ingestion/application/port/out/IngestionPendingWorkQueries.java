package br.com.gestorcobranca.ingestion.application.port.out;

import br.com.gestorcobranca.shared.pendingwork.PendingWorkCount;

import java.util.Set;
import java.util.UUID;

/** Contagens de "Minhas pendências" do módulo (consultas indexadas; RLS do tenant corrente). */
public interface IngestionPendingWorkQueries {

    /**
     * Conferências aguardando a aprovação de segunda pessoa que o usuário pode decidir: não foi ele quem
     * criou a conferência nem quem pediu a efetivação.
     *
     * @param creditorScope credores permitidos (nulo = todos)
     */
    PendingWorkCount awaitingApproval(Set<UUID> creditorScope, UUID approverId);

    /**
     * Conferências abertas pelo usuário com análise de valores concluída e alertas ainda sem decisão.
     *
     * @param creditorScope credores permitidos (nulo = todos)
     */
    PendingWorkCount openAlerts(Set<UUID> creditorScope, UUID ownerId);
}
