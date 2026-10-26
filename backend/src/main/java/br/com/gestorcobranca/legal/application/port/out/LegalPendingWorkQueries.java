package br.com.gestorcobranca.legal.application.port.out;

import br.com.gestorcobranca.shared.pendingwork.PendingWorkCount;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/** Contagens de "Minhas pendências" do módulo (consultas indexadas; RLS do tenant corrente). */
public interface LegalPendingWorkQueries {

    /**
     * Prazos pendentes com vencimento até {@code until} (inclusive vencidos) sob responsabilidade do
     * usuário: responsável do prazo ou, sem responsável no prazo, do caso jurídico; para membro de
     * escritório parceiro, os prazos sem responsável dos casos do seu escritório. {@code singleId} é o
     * do caso jurídico.
     *
     * @param creditorScope credores permitidos (nulo = todos)
     * @param lawFirmId     escritório do usuário (nulo = usuário interno)
     */
    PendingWorkCount deadlinesDue(Set<UUID> creditorScope, UUID lawFirmId, UUID userId, LocalDate today,
                                  LocalDate until);
}
