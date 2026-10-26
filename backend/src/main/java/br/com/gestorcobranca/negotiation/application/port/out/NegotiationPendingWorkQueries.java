package br.com.gestorcobranca.negotiation.application.port.out;

import br.com.gestorcobranca.shared.pendingwork.PendingWorkCount;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/** Contagens de "Minhas pendências" do módulo (consultas indexadas; RLS do tenant corrente). */
public interface NegotiationPendingWorkQueries {

    /**
     * Propostas do próprio usuário na situação informada (APPROVED: aguardando aceite do devedor;
     * ACCEPTED: aguardando formalização), com prazo pela validade.
     *
     * @param creditorScope credores permitidos (nulo = todos)
     */
    PendingWorkCount ownProposals(Set<UUID> creditorScope, UUID userId, String status, LocalDate today);
}
