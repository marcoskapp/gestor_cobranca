package br.com.gestorcobranca.shared.events.campaign;

import br.com.gestorcobranca.shared.events.IntegrationEvent;

import java.time.Instant;
import java.util.UUID;

/**
 * Régua de campanha esgotada para um caso de cobrança: todas as etapas percorridas sem resposta do
 * devedor, contato efetivo, acordo ou pagamento atribuídos à inscrição. Critério de elegibilidade
 * jurídica ("régua extrajudicial esgotada", {@code docs/tecnico/desenho/juridico.md} §B.10) — o
 * módulo legal deve considerar somente {@code campaignPurpose = EXTRAJUDICIAL_COLLECTION}.
 * Publicado uma vez por inscrição, na transação que a encerra (consumidores idempotentes).
 *
 * @param campaignPurpose EXTRAJUDICIAL_COLLECTION, REMINDER, AGREEMENT_FOLLOW_UP ou INFORMATIVE
 * @param cadenceVersion  versão da régua percorrida
 * @param stepsExecuted   etapas executadas (ações efetivas: mensagens aceitas para envio, tarefas…)
 * @param stepsSkipped    etapas puladas (porteiro, condição, inelegibilidade)
 * @param messagesSent    mensagens enfileiradas pela régua (entrega confirmada pelo communication à parte)
 * @param enrolledAt      início da inscrição
 */
public record CadenceExhausted(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID enrollmentId,
        UUID campaignId,
        String campaignPurpose,
        int cadenceVersion,
        UUID caseId,
        UUID debtorId,
        UUID creditorId,
        UUID portfolioId,
        int stepsExecuted,
        int stepsSkipped,
        int messagesSent,
        Instant enrolledAt) implements IntegrationEvent {
}
