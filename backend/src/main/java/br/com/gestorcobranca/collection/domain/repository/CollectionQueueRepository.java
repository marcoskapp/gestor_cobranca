package br.com.gestorcobranca.collection.domain.repository;

import br.com.gestorcobranca.collection.domain.model.CollectionQueue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CollectionQueueRepository {

    CollectionQueue save(CollectionQueue queue);

    Optional<CollectionQueue> findById(UUID id);

    /**
     * Fila com bloqueio pessimista ({@code select ... for update}) até o fim da transação: serializa as distribuições
     * em lote da mesma fila ("Distribuir" manual e rodada automática), que leem cargas, cursor e capacidade antes de
     * gravar. A prévia não bloqueia.
     */
    Optional<CollectionQueue> findByIdForUpdate(UUID id);

    boolean existsByCode(String code);

    List<CollectionQueue> findByActiveTrue();

    List<CollectionQueue> findAllByOrderByPriorityAscCodeAsc();
}
