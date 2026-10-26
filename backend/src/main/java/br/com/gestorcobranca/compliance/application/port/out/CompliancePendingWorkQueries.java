package br.com.gestorcobranca.compliance.application.port.out;

import br.com.gestorcobranca.shared.pendingwork.PendingWorkCount;

import java.time.Instant;

/** Contagens de "Minhas pendências" do módulo (consultas indexadas; RLS do tenant corrente). */
public interface CompliancePendingWorkQueries {

    /**
     * Solicitações de titulares em aberto com prazo legal até {@code dueBefore} (inclui as vencidas).
     *
     * @param now             vencidas: prazo anterior a este instante
     * @param startOfTomorrow vencem hoje: prazo a partir de {@code now} e antes deste instante
     */
    PendingWorkCount dataSubjectRequestsDueSoon(Instant now, Instant startOfTomorrow, Instant dueBefore);
}
