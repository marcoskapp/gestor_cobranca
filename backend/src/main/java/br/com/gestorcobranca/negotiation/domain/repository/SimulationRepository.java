package br.com.gestorcobranca.negotiation.domain.repository;

import br.com.gestorcobranca.negotiation.domain.model.simulation.NegotiationSimulation;

import java.util.Optional;
import java.util.UUID;

/** Simulações registradas (append-only). */
public interface SimulationRepository {

    NegotiationSimulation save(NegotiationSimulation simulation);

    Optional<NegotiationSimulation> findById(UUID id);
}
