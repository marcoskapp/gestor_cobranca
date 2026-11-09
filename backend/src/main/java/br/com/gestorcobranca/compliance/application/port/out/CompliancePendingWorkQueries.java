package br.com.gestorcobranca.compliance.application.port.out;

import br.com.gestorcobranca.shared.pendingwork.PendingWorkCount;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Contagens de "Minhas pendências" do módulo (consultas indexadas; RLS do tenant corrente). */
public interface CompliancePendingWorkQueries {

    /**
     * Solicitações de titulares em aberto com prazo legal até {@code dueBefore} (inclui as vencidas).
     *
     * @param now             vencidas: prazo anterior a este instante
     * @param startOfTomorrow vencem hoje: prazo a partir de {@code now} e antes deste instante
     */
    PendingWorkCount dataSubjectRequestsDueSoon(Instant now, Instant startOfTomorrow, Instant dueBefore);

    /**
     * Sugestões de marcador de vulnerabilidade pendentes. Sem prazo legal: nenhuma é "vencida"; a mais antiga
     * indica o envelhecimento.
     *
     * @param creditorScope credores do ator (nulo = todos); sugestões sem credor só entram com {@code null}
     */
    PendingWorkCount vulnerabilitySuggestionsPending(Set<UUID> creditorScope);
}
