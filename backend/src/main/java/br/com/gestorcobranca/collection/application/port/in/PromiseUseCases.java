package br.com.gestorcobranca.collection.application.port.in;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Promessas de pagamento simples (sem acordo formal), registradas por interação com tabulação de
 * promessa. Ciclo {@code PENDING → KEPT | BROKEN | CANCELLED}: cumprida quando pagamentos
 * confirmados do devedor na carteira do caso, sem acordo, alcançam o valor prometido desde o
 * registro ({@code PaymentConfirmed}); quebrada pelo job de vencimento quando passa a data
 * prometida mais a tolerância. Publica {@code PromiseStatusChanged}.
 */
public interface PromiseUseCases {

    List<PromiseView> listByCase(UUID caseId);

    PromiseView get(UUID promiseId);

    /** Cancela a promessa pendente (ex.: registrada por engano). */
    PromiseView cancel(UUID promiseId, String reason);

    /**
     * @param breaksAfter último dia em que um pagamento ainda cumpre a promessa
     */
    record PromiseView(UUID id, UUID caseId, UUID debtorId, UUID creditorId, UUID portfolioId, BigDecimal amount,
                       LocalDate dueDate, LocalDate breaksAfter, Instant promisedAt, String status,
                       BigDecimal paidAmount, UUID originInteractionId, UUID operatorId, Instant resolvedAt,
                       String resolutionReason, long version) {
    }
}
