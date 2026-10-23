package br.com.gestorcobranca.shared.events.communication;

import br.com.gestorcobranca.shared.events.IntegrationEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Pedido de transferência do atendimento para humano, publicado por um módulo que não pode
 * depender de {@code communication} (ex.: {@code negotiation} quando a proposta do assistente
 * exige aprovação — {@code REQUIRES_APPROVAL}). Consumido por {@code communication}
 * ({@code communication.handoff-requested}): as conversas abertas do caso (ou a indicada) passam
 * para a fila com motivo, resumo e perfil exigido, e o caso volta aos humanos no collection.
 * <p>
 * Módulos que já dependem de {@code communication} (assistant) chamam diretamente
 * {@code ConversationUseCases#handoffToHumans}. Sem conversa aberta para o caso, o pedido é
 * registrado apenas no caso (devolução à fila), sem efeito na caixa de entrada.
 *
 * @param conversationId  conversa específica (null = todas as conversas abertas do caso)
 * @param reasonCode      motivo estável (ex.: {@code REQUIRES_APPROVAL}, {@code DISPUTE},
 *                        {@code DEBTOR_REQUESTED_HUMAN}, {@code GUARDRAIL})
 * @param requiredProfile perfil exigido para atender (ex.: código do perfil de alçada
 *                        {@code SUPERVISOR}); null = qualquer operador
 * @param summary         resumo para o operador, <strong>sem dados pessoais</strong> (ids, valores,
 *                        condições); até 2000 caracteres; guardado criptografado
 * @param referenceType   agregado que motivou (ex.: {@code proposal}, {@code simulation})
 * @param referenceId     id do agregado
 * @param requestedByType tipo do ator que pediu: ASSISTANT, USER ou SYSTEM
 * @param requestedById   id do ator (política do assistente, usuário ou {@code Actor.SYSTEM_ID})
 */
public record HandoffRequested(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID caseId,
        UUID debtorId,
        UUID creditorId,
        UUID conversationId,
        String reasonCode,
        String requiredProfile,
        String summary,
        String referenceType,
        UUID referenceId,
        String requestedByType,
        UUID requestedById) implements IntegrationEvent {
}
