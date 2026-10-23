package br.com.gestorcobranca.collection.infrastructure.persistence;

import br.com.gestorcobranca.collection.domain.model.CollectionQueue;
import br.com.gestorcobranca.collection.domain.repository.CollectionQueueRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface CollectionQueueJpaRepository extends JpaRepository<CollectionQueue, UUID>, CollectionQueueRepository {
}
