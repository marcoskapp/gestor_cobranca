package br.com.gestorcobranca.assistant.infrastructure.persistence;

import br.com.gestorcobranca.assistant.domain.model.PixRequest;
import br.com.gestorcobranca.assistant.domain.repository.PixRequestRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

interface PixRequestJpaRepository extends JpaRepository<PixRequest, UUID>, PixRequestRepository {

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PixRequest r where r.id = :id")
    Optional<PixRequest> findByIdForUpdate(UUID id);

    @Override
    @Query("""
            select count(r) from PixRequest r
             where r.conversationId = :conversationId and r.decision = br.com.gestorcobranca.assistant.domain.model.PixDecision.ISSUED
               and r.requestedAt >= :since
               and r.deliveryStatus <> br.com.gestorcobranca.assistant.domain.model.PixDeliveryStatus.CANCELLED
            """)
    long countIssuedSince(UUID conversationId, Instant since);
}
