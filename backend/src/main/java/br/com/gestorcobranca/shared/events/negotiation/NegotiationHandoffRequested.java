package br.com.gestorcobranca.shared.events.negotiation;

import br.com.gestorcobranca.shared.events.IntegrationEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * O assistente de IA propôs condições dentro da política, mas acima da própria alçada: a proposta
 * <strong>não</strong> vai para a fila de aprovação; o atendimento deve ser transferido para um
 * humano com a proposta como sugestão ({@code docs/tecnico/desenho/negociacao-e-alcadas.md} §5).
 * <p>
 * Publicado pelo módulo {@code negotiation} na transação que registra a proposta
 * ({@code HANDED_OFF}). Consumidores previstos: {@code communication} (transferir a conversa para
 * fila/operador) e {@code assistant} (encerrar o turno com a mensagem de transferência). O humano
 * adota a sugestão ({@code POST /negotiation/proposals/{id}/adopt}) ou negocia outra proposta
 * referenciando-a; a origem do acordo ({@code ASSISTANT_HANDOFF_CONFIRMED} ×
 * {@code ASSISTANT_HANDOFF_NEGOTIATED}) é decidida comparando as condições formalizadas com esta
 * sugestão.
 *
 * @param proposalId        proposta sugerida pelo assistente (status {@code HANDED_OFF})
 * @param assistantPolicyId política do assistente (id do ator {@code ASSISTANT})
 * @param reasons           códigos das violações de alçada (ex.: {@code authority.discount_exceeded})
 * @param concessionLevel   nível da escada proposto
 * @param totalAmount       valor total da proposta (soma dos pagamentos)
 * @param channel           canal da conversa (WHATSAPP, EMAIL, SMS, CHAT, PHONE…), se informado
 */
public record NegotiationHandoffRequested(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID proposalId,
        UUID caseId,
        UUID debtorId,
        UUID creditorId,
        UUID portfolioId,
        UUID assistantPolicyId,
        UUID simulationId,
        List<String> reasons,
        int concessionLevel,
        int installments,
        BigDecimal totalAmount,
        String channel) implements IntegrationEvent {

    public NegotiationHandoffRequested {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }
}
