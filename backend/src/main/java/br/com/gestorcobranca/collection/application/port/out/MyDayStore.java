package br.com.gestorcobranca.collection.application.port.out;

import br.com.gestorcobranca.collection.application.port.in.MyDayQueries.QueueDay;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Contagens do "Seu dia" (consultas indexadas; RLS do tenant corrente). {@code creditorScope} nulo =
 * todos os credores; vazio = nenhum. Nenhuma consulta devolve dado de devedor.
 */
public interface MyDayStore {

    /** Casos ativos (não encerrados) dos usuários. */
    long activeCases(Set<UUID> creditorScope, Set<UUID> userIds);

    /**
     * Retornos e acompanhamentos em aberto dos usuários até o fim do dia: pendentes a partir de
     * {@code now} ({@code today}); perdidos desde {@code missedSince} ou pendentes com horário anterior
     * a {@code now} ({@code overdue}).
     */
    TaskCounts tasks(Set<UUID> creditorScope, Set<UUID> userIds, Instant now, Instant startOfTomorrow,
                     Instant missedSince);

    /** Promessas pendentes nos casos ativos dos usuários: vencendo hoje e já vencidas (ainda na tolerância). */
    PromiseCounts promises(Set<UUID> creditorScope, Set<UUID> userIds, LocalDate today);

    /** Interações, contatos efetivos e promessas registrados pelo usuário desde {@code since}. */
    OwnCounts ownResults(Set<UUID> creditorScope, UUID userId, Instant since);

    /** Filas ativas atendidas pelas equipes, com os casos aguardando operador (e os de próxima ação vencida). */
    List<QueueDay> queues(Set<UUID> creditorScope, Set<UUID> teamIds, Instant now);

    record TaskCounts(long today, long overdue) {
    }

    record PromiseCounts(long dueToday, long awaitingPayment) {
    }

    record OwnCounts(long interactions, long effectiveContacts, long promises) {
    }
}
