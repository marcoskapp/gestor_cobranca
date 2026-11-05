package br.com.gestorcobranca.collection.domain.repository;

import br.com.gestorcobranca.collection.domain.model.DisputeCreditorRequest;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DisputeCreditorRequestRepository {

    DisputeCreditorRequest save(DisputeCreditorRequest request);

    Optional<DisputeCreditorRequest> findById(UUID id);

    List<DisputeCreditorRequest> findByDisputeIdOrderBySequenceNumberAsc(UUID disputeId);

    long countByDisputeId(UUID disputeId);

    /**
     * Pedidos enviados e sem resposta com algo a fazer: lembrete ainda não enfileirado e prazo até
     * {@code reminderUntil}, ou prazo vencido (antes de {@code today}) ainda não sinalizado.
     */
    List<DisputeCreditorRequest> findFollowUpCandidates(LocalDate reminderUntil, LocalDate today, int limit);
}
