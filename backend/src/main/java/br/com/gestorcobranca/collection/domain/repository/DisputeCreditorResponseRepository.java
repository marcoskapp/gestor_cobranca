package br.com.gestorcobranca.collection.domain.repository;

import br.com.gestorcobranca.collection.domain.model.DisputeCreditorResponse;

import java.util.List;
import java.util.UUID;

public interface DisputeCreditorResponseRepository {

    DisputeCreditorResponse save(DisputeCreditorResponse response);

    DisputeCreditorResponse saveAndFlush(DisputeCreditorResponse response);

    List<DisputeCreditorResponse> findByDisputeIdOrderByReceivedAtAsc(UUID disputeId);
}
