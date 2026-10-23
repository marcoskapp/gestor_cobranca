package br.com.gestorcobranca.negotiation.application.service;

import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.CompositionView;
import br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases.OfferView;

/**
 * Memória de cálculo de uma proposta (jsonb {@code proposal.calculation}): composição revalidada na
 * criação e a oferta resultante (descontos, cronograma, violações de alçada), com a versão do motor.
 */
record ProposalCalculation(CompositionView composition, OfferView offer, int engineVersion) {
}
