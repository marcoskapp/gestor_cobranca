package br.com.gestorcobranca.collection.infrastructure.persistence;

import br.com.gestorcobranca.collection.domain.model.CollectionQueue;
import br.com.gestorcobranca.collection.domain.repository.CollectionQueueRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

interface CollectionQueueJpaRepository extends JpaRepository<CollectionQueue, UUID>, CollectionQueueRepository {

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select q from CollectionQueue q where q.id = :id")
    Optional<CollectionQueue> findByIdForUpdate(@Param("id") UUID id);
}
