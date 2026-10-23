package br.com.gestorcobranca.collection.domain.repository;

import br.com.gestorcobranca.collection.domain.model.CollectionQueue;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CollectionQueueRepository {

    CollectionQueue save(CollectionQueue queue);

    Optional<CollectionQueue> findById(UUID id);

    boolean existsByCode(String code);

    List<CollectionQueue> findByActiveTrue();

    List<CollectionQueue> findAllByOrderByPriorityAscCodeAsc();
}
