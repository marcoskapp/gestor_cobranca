package br.com.gestorcobranca.settlement.application.port.in;

import br.com.gestorcobranca.settlement.application.port.in.CommissionRuleUseCases.CalculationView;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Recebimentos e razão de comissão da assessoria (append-only), com a memória de cálculo de cada
 * lançamento. Consulta: {@code COMMISSION_VIEW}; reapuração de pendentes: {@code COMMISSION_MANAGE}.
 */
public interface CommissionLedgerQueries {

    PageResponse<ReceiptView> receipts(ReceiptFilter filter, PageQuery page);

    /** Recebimento com detalhe (parcelas, títulos, componentes), lançamento e estornos. */
    ReceiptDetailView receipt(UUID receiptId);

    /** Tenta apurar os recebimentos do credor aguardando regra (ex.: contrato resolvido depois); devolve quantos. */
    int ratePending(UUID creditorId);

    /**
     * @param ratingStatus PENDING_RULES ou RATED
     * @param inStatement  verdadeiro = já prestados; falso = ainda não
     */
    record ReceiptFilter(UUID creditorId, UUID agreementId, LocalDate from, LocalDate to, String ratingStatus,
                         Boolean inStatement) {
    }

    /**
     * @param kind        PAYMENT ou REVERSAL
     * @param paymentType LUMP_SUM ou INSTALLMENTS
     * @param receivedBy  AGENCY ou CREDITOR
     */
    record ReceiptView(UUID id, String kind, UUID allocationId, UUID paymentId, UUID creditorId, UUID portfolioId,
                       UUID contractId, UUID agreementId, UUID titleId, String titleReference, BigDecimal amount,
                       BigDecimal honorarium, Integer daysPastDue, String paymentType, String receivedBy,
                       boolean renegotiation, String method, LocalDate paidOn, LocalDate competenceDate,
                       String ratingStatus, String pendingReason, UUID reversesReceiptId, String reversalReason,
                       UUID statementId, EntryView entry) {
    }

    /** Lançamento do razão (valores negativos no estorno). */
    record EntryView(UUID id, String kind, UUID ruleSetId, int ruleVersion, UUID reversesEntryId,
                     CalculationView calculation) {
    }

    record ReceiptDetailView(ReceiptView receipt, Map<String, Object> detail, List<ReceiptView> reversals) {
    }
}
