package br.com.gestorcobranca.shared.events.legal;

import br.com.gestorcobranca.shared.events.IntegrationEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Custa ou despesa de um caso jurídico lançada, cancelada ou reembolsada (módulo {@code legal}).
 * Consumida pela prestação de contas ({@code settlement}): despesas reembolsáveis adiantadas pela
 * assessoria ou por escritório parceiro são deduzidas do valor a repassar ao credor; as adiantadas
 * pelo próprio credor aparecem só como informação. Sem descrição livre (pode conter dados do processo).
 *
 * @param type        natureza ({@code COURT_FEES}, {@code PROCEDURAL_COSTS}, {@code BAILIFF},
 *                    {@code REGISTRY_FEES}, {@code CREDIT_BUREAU_FEES}, {@code APPRAISAL}, {@code AUCTION},
 *                    {@code PUBLICATION}, {@code CERTIFICATES}, {@code TRAVEL}, {@code OTHER})
 * @param advancedBy  quem adiantou: {@code CREDITOR}, {@code AGENCY} ou {@code LAW_FIRM}
 * @param status      {@code RECORDED} (lançada), {@code CANCELLED} (estorno do lançamento) ou
 *                    {@code REIMBURSED} (reembolsada a quem adiantou fora da prestação de contas)
 * @param statusDate  data do cancelamento ou do reembolso (nula em {@code RECORDED})
 */
public record LegalExpenseChanged(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID expenseId,
        UUID matterId,
        UUID creditorId,
        String type,
        BigDecimal amount,
        LocalDate incurredOn,
        String advancedBy,
        boolean reimbursable,
        boolean hasReceipt,
        String status,
        LocalDate statusDate) implements IntegrationEvent {
}
