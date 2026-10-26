package br.com.gestorcobranca.scoring.application.port.out;

import br.com.gestorcobranca.shared.pendingwork.PendingWorkCount;

import java.util.UUID;

/** Contagens de "Minhas pendências" do módulo (RLS do tenant corrente). */
public interface ScoringPendingWorkQueries {

    /**
     * Desafiante validado aguardando decisão (promover ou rejeitar) que o usuário pode decidir: não foi
     * ele quem pediu o treino ou criou o scorecard (segregação de {@code ModelGovernanceService#promote}).
     */
    PendingWorkCount challengerAwaitingDecision(UUID userId);
}
