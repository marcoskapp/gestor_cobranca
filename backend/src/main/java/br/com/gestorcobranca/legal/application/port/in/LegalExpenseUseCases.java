package br.com.gestorcobranca.legal.application.port.in;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Custas e despesas do caso jurídico ({@code LEGAL_EXPENSE_MANAGE}): quem pode adiantar é
 * parâmetro da assessoria (padrão: só o credor); com o controle de orçamento BLOCK (padrão), a
 * despesa exige autorização do credor vigente e não pode ultrapassar o orçamento aprovado.
 * Cancelamento e reembolso preservam o lançamento.
 */
public interface LegalExpenseUseCases {

    ExpenseView record(UUID matterId, ExpenseCommand command);

    ExpenseView cancel(UUID expenseId, String reason);

    ExpenseView reimburse(UUID expenseId, LocalDate reimbursedOn, String note);

    List<ExpenseView> list(UUID matterId);

    /**
     * @param type       COURT_FEES, PROCEDURAL_COSTS, BAILIFF, REGISTRY_FEES, CREDIT_BUREAU_FEES, APPRAISAL,
     *                   AUCTION, PUBLICATION, CERTIFICATES, TRAVEL ou OTHER
     * @param advancedBy CREDITOR, AGENCY ou LAW_FIRM
     * @param receiptDocumentId comprovante (documento do caso jurídico)
     */
    record ExpenseCommand(String type, String description, BigDecimal amount, LocalDate incurredOn, String advancedBy,
                          boolean reimbursable, UUID lawsuitId, UUID receiptDocumentId) {
    }

    record ExpenseView(UUID id, UUID matterId, UUID lawsuitId, String type, String description, BigDecimal amount,
                       LocalDate incurredOn, String advancedBy, UUID lawFirmId, boolean reimbursable,
                       UUID receiptDocumentId, boolean overBudget, String status, LocalDate statusDate,
                       String statusReason, Instant recordedAt, UUID recordedBy) {
    }
}
