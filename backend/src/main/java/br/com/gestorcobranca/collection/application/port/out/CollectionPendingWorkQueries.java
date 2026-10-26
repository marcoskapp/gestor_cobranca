package br.com.gestorcobranca.collection.application.port.out;

import br.com.gestorcobranca.shared.pendingwork.PendingWorkCount;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/** Contagens de "Minhas pendências" do módulo (consultas indexadas; RLS do tenant corrente). */
public interface CollectionPendingWorkQueries {

    /**
     * Retornos e acompanhamentos do usuário em aberto até o fim do dia: pendentes com horário até
     * {@code startOfTomorrow} e perdidos a partir de {@code missedSince} (a mesma janela da agenda).
     * Vencidos: perdidos ou com horário anterior a {@code now}.
     *
     * @param creditorScope credores permitidos (nulo = todos)
     */
    PendingWorkCount tasksDue(Set<UUID> creditorScope, UUID userId, Instant now, Instant startOfTomorrow,
                              Instant missedSince);

    /**
     * Contestações em aberto (abertas ou em análise) com prazo de resposta até {@code until}, inclusive
     * vencidas.
     *
     * @param creditorScope credores permitidos (nulo = todos)
     */
    PendingWorkCount disputesDue(Set<UUID> creditorScope, LocalDate today, LocalDate until);
}
