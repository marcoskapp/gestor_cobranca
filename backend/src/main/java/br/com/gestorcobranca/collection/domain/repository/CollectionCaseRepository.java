package br.com.gestorcobranca.collection.domain.repository;

import br.com.gestorcobranca.collection.domain.model.CollectionCase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CollectionCaseRepository {

    CollectionCase save(CollectionCase c);

    Optional<CollectionCase> findById(UUID id);

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

    Page<CollectionCase> search(CaseSearchCriteria criteria, Pageable pageable);
}
