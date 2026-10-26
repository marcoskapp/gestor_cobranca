package br.com.gestorcobranca.communication.application.port.out;

import br.com.gestorcobranca.shared.pendingwork.PendingWorkCount;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Contagens de "Minhas pendências" do módulo (consultas indexadas; RLS do tenant corrente). */
public interface CommunicationPendingWorkQueries {

    /**
     * Conversas abertas aguardando resposta: do próprio usuário ({@code ownerUserId} informado) ou na
     * fila ({@code ownerUserId} nulo: dono fila ou nenhum). Vencidas: aguardando desde antes do limite
     * do SLA de primeira resposta do canal.
     *
     * @param creditorScope   credores permitidos (nulo = todos)
     * @param breachedBefore  por canal, o instante antes do qual a espera estoura a meta
     */
    PendingWorkCount awaitingReply(Set<UUID> creditorScope, UUID ownerUserId, Map<String, Instant> breachedBefore);

    /**
     * Rascunhos de template aguardando a decisão do provedor ou já aprovados e ainda não publicados.
     * Template da assessoria (sem credor) só entra para quem tem todos os credores ({@code creditorScope}
     * nulo), como nos comandos de template.
     *
     * @param creditorScope credores permitidos (nulo = todos)
     */
    PendingWorkCount templatesAwaitingApproval(Set<UUID> creditorScope);
}
