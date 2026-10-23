/**
 * Interface pública do módulo negotiation: casos de uso e seus records de entrada/saída. Único
 * pacote que outros módulos podem referenciar. Os termos de política expostos
 * ({@code PolicyTerms}/{@code PolicyRules}) são valores imutáveis do contrato do caso de uso.
 * <p>
 * Para o módulo assistant (futuro): {@link br.com.gestorcobranca.negotiation.application.port.in.AuthorityUseCases#assign}
 * com sujeito {@code ASSISTANT_POLICY} vincula a política do assistente a um perfil de alçada, e
 * {@link br.com.gestorcobranca.negotiation.application.port.in.NegotiationSimulationUseCases} é a
 * ferramenta de simulação/validação, executada com {@code Actor.assistant(...)}.
 */
@NamedInterface("usecases")
package br.com.gestorcobranca.negotiation.application.port.in;

import org.springframework.modulith.NamedInterface;
