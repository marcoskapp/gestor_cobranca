package br.com.gestorcobranca.shared.events.collection;

import br.com.gestorcobranca.shared.events.IntegrationEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Promessa de pagamento simples registrada, cumprida, quebrada ou cancelada. Fonte do indicador
 * "promessas cumpridas" (KEPT ÷ promessas vencidas) do módulo incentive.
 * <p>
 * Situações ({@code status}): {@code PENDING} (registrada), {@code KEPT} (pagamentos confirmados
 * alcançaram o valor), {@code BROKEN} (prazo de tolerância vencido), {@code CANCELLED}
 * (substituída por nova promessa, por acordo formalizado, ou cancelada manualmente). Um estorno
 * pode devolver uma promessa cumprida a {@code PENDING} ({@code previousStatus = KEPT}).
 *
 * @param operatorId     quem registrou a promessa (null = automática)
 * @param amount         valor prometido
 * @param paidAmount     valor confirmado considerado até agora
 * @param dueDate        data prometida
 * @param breaksAfter    último dia em que um pagamento ainda cumpre a promessa
 * @param previousStatus null no registro
 */
public record PromiseStatusChanged(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID promiseId,
        UUID caseId,
        UUID debtorId,
        UUID creditorId,
        UUID portfolioId,
        UUID operatorId,
        BigDecimal amount,
        BigDecimal paidAmount,
        LocalDate dueDate,
        LocalDate breaksAfter,
        String previousStatus,
        String status) implements IntegrationEvent {
}
