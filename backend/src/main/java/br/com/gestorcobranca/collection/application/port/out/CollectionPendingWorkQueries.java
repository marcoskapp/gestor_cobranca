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
     * Contestações em aberto do responsável com o prazo mais próximo (interno ou de resposta) até {@code until},
     * inclusive vencidas. Vencida: prazo interno ou de resposta antes de hoje.
     *
     * @param creditorScope credores permitidos (nulo = todos)
     */
    PendingWorkCount disputesOfAssignee(Set<UUID> creditorScope, UUID userId, LocalDate today, LocalDate until);

    /**
     * Supervisão: contestações em aberto sem responsável ou com prazo (interno ou de resposta) vencido.
     *
     * @param creditorScope credores permitidos (nulo = todos)
     */
    PendingWorkCount disputesForSupervision(Set<UUID> creditorScope, LocalDate today);

    /**
     * Contestações resolvidas com a resposta ao devedor pendente (sem prazo; idade desde a resolução).
     *
     * @param userId responsável (nulo = todas do escopo)
     */
    PendingWorkCount debtorNoticesPending(Set<UUID> creditorScope, UUID userId);

    /**
     * Contestações com ajuste sugerido no título pendente (sem prazo; idade desde a resolução).
     *
     * @param userId responsável (nulo = todas do escopo)
     */
    PendingWorkCount adjustmentsPending(Set<UUID> creditorScope, UUID userId);

    /**
     * Respostas do credor recebidas por e-mail aguardando a confirmação do analista (idade desde o recebimento; o item
     * único leva à contestação).
     *
     * @param userId responsável pela contestação (nulo = todas do escopo)
     */
    PendingWorkCount mailRepliesPending(Set<UUID> creditorScope, UUID userId);

    /** E-mails do credor em quarentena (gestão; idade desde o recebimento; o item único leva à contestação). */
    PendingWorkCount mailRepliesQuarantined(Set<UUID> creditorScope);
}
