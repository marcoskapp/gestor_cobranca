package br.com.gestorcobranca.assistant.application.port.out;

import br.com.gestorcobranca.shared.pendingwork.PendingWorkCount;

import java.util.Set;
import java.util.UUID;

/** Contagens de "Minhas pendências" do módulo (consultas indexadas; RLS do tenant corrente). */
public interface AssistantPendingWorkQueries {

    /** Turnos sorteados para revisão humana ainda não avaliados. @param creditorScope nulo = todos */
    PendingWorkCount pendingReviews(Set<UUID> creditorScope);
}
