package br.com.gestorcobranca.negotiation.infrastructure.persistence;

import br.com.gestorcobranca.negotiation.domain.model.simulation.NegotiationSimulation;
import br.com.gestorcobranca.negotiation.domain.repository.SimulationRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface SimulationJpa extends JpaRepository<NegotiationSimulation, UUID>, SimulationRepository {
}
