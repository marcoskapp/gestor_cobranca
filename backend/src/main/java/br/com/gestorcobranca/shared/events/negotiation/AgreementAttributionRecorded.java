package br.com.gestorcobranca.shared.events.negotiation;

import br.com.gestorcobranca.shared.events.IntegrationEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Participantes de um acordo registrados na formalização (insumo da remuneração variável, ver
 * {@code docs/tecnico/desenho/remuneracao-variavel.md} §3). Publicado pelo módulo {@code negotiation} na
 * mesma transação da formalização; o módulo {@code incentive} nunca reconstrói a atribuição a
 * partir de logs.
 *
 * @param teamId              equipe dona da fila/carteira do caso no momento do acordo (destino do
 *                            pool quando o acordo é do assistente); null se o caso não tem fila de equipe
 * @param origin              {@code HUMAN}, {@code HUMAN_COPILOT}, {@code ASSISTANT_HANDOFF_NEGOTIATED},
 *                            {@code ASSISTANT_HANDOFF_CONFIRMED}, {@code ASSISTANT_AUTONOMOUS},
 *                            {@code PORTAL_SELF_SERVICE}, {@code CAMPAIGN}. A distinção
 *                            {@code HANDOFF_CONFIRMED} × {@code HANDOFF_NEGOTIATED} é decidida no
 *                            servidor comparando a proposta formalizada com a sugestão do handoff
 * @param channel             canal da formalização (PHONE, WHATSAPP, EMAIL, SMS, PORTAL, IN_PERSON)
 * @param participants        atores e papéis; ao menos o {@code CLOSER} (humano ou assistente). O
 *                            {@code INFLUENCER} é opcional: {@code incentive} o determina pela janela
 *                            de influência do plano e só usa o informado aqui como contingência
 * @param concessionLevel     nível da escada de concessões em que o acordo fechou (0 = condição base)
 * @param envelopeUsagePercent desconto concedido ÷ desconto máximo permitido pela política (0–100)
 * @param daysPastDue         dias de atraso do título mais antigo coberto, na formalização (a faixa
 *                            é derivada pelo consumidor)
 * @param agreementAmount     valor total do acordo
 * @param updatedBalance      saldo atualizado dos títulos cobertos antes do desconto
 * @param discountAmount      desconto concedido sobre o saldo atualizado
 * @param downPaymentAmount   valor da entrada (0 se não houver)
 * @param replacedAgreementId acordo substituído quando é renegociação (RB05)
 */
public record AgreementAttributionRecorded(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID agreementId,
        UUID caseId,
        UUID debtorId,
        UUID creditorId,
        UUID portfolioId,
        UUID teamId,
        String origin,
        String channel,
        List<Participant> participants,
        Integer concessionLevel,
        BigDecimal envelopeUsagePercent,
        Integer daysPastDue,
        BigDecimal agreementAmount,
        BigDecimal updatedBalance,
        BigDecimal discountAmount,
        BigDecimal downPaymentAmount,
        UUID replacedAgreementId,
        Instant formalizedAt) implements IntegrationEvent {

    public AgreementAttributionRecorded {
        participants = participants == null ? List.of() : List.copyOf(participants);
    }

    /**
     * @param actorType {@code USER}, {@code ASSISTANT}, {@code DEBTOR} (portal) ou {@code SYSTEM} (campanha)
     * @param actorId   usuário, política do assistente, devedor ou {@code Actor.SYSTEM_ID}
     * @param role      {@code CLOSER}, {@code ORIGINATOR}, {@code PRE_NEGOTIATOR}, {@code APPROVER},
     *                  {@code INFLUENCER}
     */
    public record Participant(String actorType, UUID actorId, String role) {
    }
}
