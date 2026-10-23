package br.com.gestorcobranca.collection.domain.repository;

import br.com.gestorcobranca.collection.domain.model.CollectionCase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface CollectionCaseRepository {

    CollectionCase save(CollectionCase c);

    Optional<CollectionCase> findById(UUID id);

    /** Devedor do caso, sem carregar a entidade (para obter o lock do devedor antes da leitura). */
    Optional<UUID> findDebtorIdById(UUID id);

    List<CollectionCase> findAllByIdIn(Collection<UUID> ids);

    /** Casos ativos (não encerrados) do devedor, em todas as carteiras. */
    List<CollectionCase> findActiveByDebtorId(UUID debtorId);

    /** Todos os casos do devedor, inclusive encerrados, do mais recente ao mais antigo. */
    List<CollectionCase> findByDebtorIdOrderByOpenedAtDesc(UUID debtorId);

    /** Ids de casos ativos em ordem de id, a partir de {@code afterId} (exclusivo; nulo = início). */
    List<UUID> findActiveIdsAfter(UUID afterId, int limit);

    List<UUID> findActiveIdsByPortfolioAfter(UUID portfolioId, UUID afterId, int limit);

    List<UUID> findActiveIdsByCreditorAfter(UUID creditorId, UUID afterId, int limit);

    /** Casos ativos atribuídos ao operador (redistribuição). */
    List<CollectionCase> findActiveByAssignee(UUID userId, int limit);

    /** Caso ativo do devedor na carteira (no máximo um, garantido por índice único). */
    Optional<CollectionCase> findActiveByDebtorAndPortfolio(UUID debtorId, UUID portfolioId);

    /** Casos ativos do devedor no credor, em todas as carteiras do credor. */
    List<CollectionCase> findActiveByDebtorAndCreditor(UUID debtorId, UUID creditorId);

    /** Casos ativos atribuídos a operadores na fila, por operador (carga para distribuição e capacidade). */
    Map<UUID, Long> countActiveAssignedByUser(UUID queueId);

    /** Casos ativos na fila sem operador (inclui inelegíveis e com retorno futuro). */
    long countWaiting(UUID queueId);

    /** Casos disponíveis para "próximo caso" no instante (mesmos critérios do {@code CaseSelector}). */
    long countAvailable(UUID queueId, Instant now);

    /** Casos ativos atribuídos ao operador, em todas as filas. */
    long countActiveByAssignee(UUID userId);

    /** Casos ativos atribuídos ao operador na fila. */
    long countActiveAssigned(UUID queueId, UUID userId);

    /** Ids de casos ativos do devedor (recalculo de elegibilidade), opcionalmente de um credor. */
    List<UUID> findActiveIdsByDebtor(UUID debtorId, UUID creditorId);

    Page<CollectionCase> search(CaseSearchCriteria criteria, Pageable pageable);
}
