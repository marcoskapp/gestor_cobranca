package br.com.gestorcobranca.assistant.domain.repository;

import br.com.gestorcobranca.assistant.domain.model.PixRequest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PixRequestRepository {

    PixRequest save(PixRequest request);

    Optional<PixRequest> findById(UUID id);

    /** Bloqueia o pedido para a entrega (um envio por pedido entre instâncias). */
    Optional<PixRequest> findByIdForUpdate(UUID id);

    List<PixRequest> findByInstructionId(UUID instructionId);

    List<PixRequest> findByTurnIdOrderByRequestedAtAsc(UUID turnId);

    /** Pix gerados na conversa desde o instante (os cancelados com o turno não contam). */
    long countIssuedSince(UUID conversationId, Instant since);
}
