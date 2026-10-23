package br.com.gestorcobranca.settlement.application.port.in;

import br.com.gestorcobranca.settlement.application.port.in.CommissionLedgerQueries.ReceiptView;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Demonstrativo / prestação de contas por credor (F22): {@code DRAFT → CALCULATED → REVIEWED → APPROVED → SENT →
 * ACKNOWLEDGED | CONTESTED}. Calcular, revisar, ajustar, enviar e registrar a resposta do credor:
 * {@code SETTLEMENT_CLOSE}; aprovar (inclusive a segunda aprovação de ajustes): {@code SETTLEMENT_APPROVE},
 * nunca por quem calculou ou pediu ajuste; consulta: {@code SETTLEMENT_VIEW}. Escopo por credor.
 */
public interface SettlementStatementUseCases {

    /**
     * Novo demonstrativo em aberto (um por credor). Sem datas: o ciclo seguinte ao último demonstrativo
     * (ou o último ciclo encerrado) pelo dia de fechamento do contrato.
     */
    StatementView create(CreateStatementCommand command);

    PeriodSuggestion suggestPeriod(UUID creditorId);

    StatementView get(UUID statementId);

    PageResponse<StatementView> search(StatementFilter filter, PageQuery page);

    PageResponse<ReceiptView> receipts(UUID statementId, PageQuery page);

    List<ExpenseView> expenses(UUID statementId);

    StatementView calculate(UUID statementId);

    StatementView review(UUID statementId);

    StatementView approve(UUID statementId);

    StatementView send(UUID statementId, SendCommand command);

    StatementView acknowledge(UUID statementId, String note);

    StatementView contest(UUID statementId, String reason);

    StatementView cancel(UUID statementId, String reason);

    AdjustmentView requestAdjustment(UUID statementId, AdjustmentCommand command);

    AdjustmentView approveAdjustment(UUID adjustmentId, String note);

    AdjustmentView rejectAdjustment(UUID adjustmentId, String note);

    List<AdjustmentView> adjustments(UUID statementId);

    record CreateStatementCommand(UUID creditorId, LocalDate periodStart, LocalDate periodEnd) {
    }

    record PeriodSuggestion(UUID creditorId, LocalDate periodStart, LocalDate periodEnd, Integer settlementDay) {
    }

    record StatementFilter(UUID creditorId, String status) {
    }

    /**
     * @param channel   EMAIL, PORTAL, API, LETTER ou OTHER
     * @param exportIds arquivos enviados (deste demonstrativo); ao menos um
     */
    record SendCommand(String channel, String reference, List<UUID> exportIds) {
    }

    /**
     * @param category COMMISSION_CORRECTION, PRIOR_PERIOD_CORRECTION, EXPENSE_REIMBURSEMENT,
     *                 CONTESTATION_SETTLEMENT ou OTHER
     * @param amount   efeito com sinal no valor a repassar (positivo a favor do credor)
     */
    record AdjustmentCommand(String category, BigDecimal amount, String reason, UUID relatedStatementId) {
    }

    record AdjustmentView(UUID id, UUID statementId, String category, BigDecimal amount, String reason,
                          UUID relatedStatementId, String status, UUID requestedBy, Instant requestedAt, UUID decidedBy,
                          Instant decidedAt, String decisionNote) {
    }

    /**
     * @param effect DEDUCT (deduzida do repasse) ou INFORMATIVE
     * @param kind   CHARGE ou CANCELLATION
     */
    record ExpenseView(UUID id, String kind, UUID legalExpenseId, UUID matterId, String expenseType, BigDecimal amount,
                       LocalDate incurredOn, String advancedBy, boolean reimbursable, boolean hasReceipt, String effect,
                       LocalDate competenceDate, String status, UUID statementId) {
    }

    /**
     * Totais do demonstrativo (RB08): recebido (pela assessoria e direto pelo credor), estornado, recuperado
     * líquido, conciliado, honorários da assessoria e do credor, comissão e valores fixos, despesas
     * deduzidas, ajustes e o líquido a repassar (negativo: o credor deve à assessoria). Informativos do
     * período: valor negociado, comissão prevista (acordos em aberto, não é recuperação), pendências de
     * conciliação e de regra e divergências de conciliação abertas.
     */
    record TotalsView(BigDecimal receivedByAgency, BigDecimal receivedByCreditor, BigDecimal reversed,
                      BigDecimal recovered, BigDecimal reconciled, BigDecimal agencyHonorarium,
                      BigDecimal creditorHonorarium, BigDecimal commission, BigDecimal fixedFees, BigDecimal expenses,
                      BigDecimal adjustments, BigDecimal netTransfer, BigDecimal transferred, BigDecimal outstanding,
                      BigDecimal negotiated, BigDecimal forecastCommission, BigDecimal pendingReconciliation,
                      BigDecimal pendingRules, int openDivergences, int receipts) {
    }

    record StatementView(UUID id, UUID creditorId, int sequenceNumber, LocalDate periodStart, LocalDate periodEnd,
                         String status, TotalsView totals, Map<String, Object> calculation, Instant calculatedAt,
                         UUID calculatedBy, Instant reviewedAt, UUID reviewedBy, Instant approvedAt, UUID approvedBy,
                         Instant sentAt, UUID sentBy, String sentChannel, String sentReference,
                         Instant creditorResponseAt, UUID creditorResponseBy, String creditorResponseNote,
                         String cancelledReason, long version) {
    }
}
