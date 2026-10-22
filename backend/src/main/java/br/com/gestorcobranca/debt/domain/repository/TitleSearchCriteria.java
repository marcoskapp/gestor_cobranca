package br.com.gestorcobranca.debt.domain.repository;

import br.com.gestorcobranca.debt.domain.model.TitleStatus;

import java.time.LocalDate;
import java.util.Collection;
import java.util.UUID;

/**
 * Filtros de busca de títulos; campos nulos não filtram.
 *
 * @param allowedCreditorIds credores visíveis ao ator; {@code null} = sem restrição
 * @param statuses           status aceitos; {@code null} = todos, vazio = nenhum
 * @param dueOnOrAfter       vencimento a partir de (já combinado com o atraso máximo)
 * @param dueOnOrBefore      vencimento até (já combinado com o atraso mínimo)
 */
public record TitleSearchCriteria(Collection<UUID> allowedCreditorIds, UUID creditorId, UUID portfolioId,
                                  UUID debtorId, Collection<TitleStatus> statuses, LocalDate dueOnOrAfter,
                                  LocalDate dueOnOrBefore) {
}
