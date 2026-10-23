package br.com.gestorcobranca.legal.domain.repository;

import br.com.gestorcobranca.legal.domain.model.DeadlineStatus;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

/**
 * Agenda de prazos (todos os filtros opcionais).
 *
 * @param creditorScope credores permitidos (nulo = todos)
 * @param lawFirmScope  restrição de escritório do ator: só prazos de casos atribuídos ao escritório
 */
public record DeadlineSearchCriteria(Set<UUID> creditorScope, UUID lawFirmScope, UUID matterId,
                                     Set<DeadlineStatus> statuses, LocalDate dueFrom, LocalDate dueTo,
                                     UUID responsibleUserId) {
}
