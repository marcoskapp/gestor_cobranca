package br.com.gestorcobranca.shared.events.settlement;

import br.com.gestorcobranca.shared.events.IntegrationEvent;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Demonstrativo de prestação de contas aprovado (fechado e imutável) para um credor (módulo
 * {@code settlement}, F22). Insumo para o {@code legal} (despesas jurídicas reembolsadas pelo
 * credor no demonstrativo — {@code reimbursedLegalExpenseIds}) e para o {@code analytics}.
 *
 * @param recoveredAmount       recuperação líquida de estornos incluída no demonstrativo
 * @param commissionAmount      comissão da assessoria (percentual + valores fixos)
 * @param agencyHonorarium      honorários cobrados do devedor que pertencem à assessoria
 * @param reimbursedExpenses    despesas reembolsáveis deduzidas do repasse
 * @param netTransferAmount     valor líquido a repassar ao credor (negativo: o credor deve à assessoria)
 * @param reimbursedLegalExpenseIds despesas do legal ({@code LegalExpenseChanged.expenseId}) deduzidas
 */
public record SettlementClosed(
        UUID eventId,
        UUID tenantId,
        Instant occurredAt,
        UUID statementId,
        UUID creditorId,
        LocalDate periodStart,
        LocalDate periodEnd,
        BigDecimal recoveredAmount,
        BigDecimal commissionAmount,
        BigDecimal agencyHonorarium,
        BigDecimal reimbursedExpenses,
        BigDecimal netTransferAmount,
        List<UUID> reimbursedLegalExpenseIds) implements IntegrationEvent {

    public SettlementClosed {
        reimbursedLegalExpenseIds = reimbursedLegalExpenseIds == null ? List.of()
                : List.copyOf(reimbursedLegalExpenseIds);
    }
}
