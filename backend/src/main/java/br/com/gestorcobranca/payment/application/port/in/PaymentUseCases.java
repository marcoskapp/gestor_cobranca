package br.com.gestorcobranca.payment.application.port.in;

import br.com.gestorcobranca.negotiation.application.port.in.AgreementBreakSuspensionUseCases.BreakSuspensionView;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Pagamentos (F14, RB06): consulta ({@code PAYMENT_VIEW}), registro de pagamento externo com
 * evidência obrigatória ({@code PAYMENT_REGISTER_EXTERNAL}), revisão por outra pessoa acima do limite
 * de risco ({@code PAYMENT_REVIEW}), estorno compensatório ({@code PAYMENT_REVERSE}) e realocação de
 * sobras e de valores não aplicados pelo negotiation ({@code RECONCILIATION_MANAGE}).
 * <p>
 * Cada alocação a acordo ou títulos é publicada como {@code PaymentConfirmed} com
 * {@code paymentId} = id da alocação (veja {@link AllocationView#id()}).
 */
public interface PaymentUseCases {

    PaymentView get(UUID paymentId);

    PageResponse<PaymentView> search(PaymentFilter filter, PageQuery page);

    /** Pagamento dono da alocação (o {@code paymentId} publicado nos eventos). */
    PaymentView findByAllocation(UUID allocationId);

    PaymentView registerExternal(ExternalPaymentCommand command, EvidenceUpload evidence, String idempotencyKey);

    /** Pagamentos informados aguardando revisão, exceto os informados pelo próprio revisor. */
    PageResponse<PaymentView> reviewQueue(PageQuery page);

    PaymentView approve(UUID paymentId, String note);

    PaymentView reject(UUID paymentId, String note);

    PaymentView reverse(UUID paymentId, ReverseCommand command);

    /** Pagamentos com saldo não alocado ou não aplicado pelo negotiation (fila de realocação). */
    PageResponse<PaymentView> pendingReallocation(PageQuery page);

    PaymentView reallocate(UUID paymentId, ReallocateCommand command);

    /** Evidência do pagamento externo. Registra {@code FILE_DOWNLOADED}. */
    EvidenceContent openEvidence(UUID paymentId);

    /**
     * @param kind      AGREEMENT (parcelas de um acordo) ou TITLES (títulos avulsos)
     * @param itemIds   parcelas (AGREEMENT) ou títulos (TITLES) em ordem de preferência; vazio = todas
     *                  as parcelas em aberto do acordo
     * @param strategy  títulos: OLDEST_FIRST (padrão) ou PROPORTIONAL
     */
    record TargetInput(String kind, UUID agreementId, List<UUID> itemIds, String strategy) {

        public TargetInput {
            itemIds = itemIds == null ? List.of() : List.copyOf(itemIds);
        }
    }

    /**
     * @param method  PIX, BOLETO, CARD, TRANSFER, CASH ou OTHER
     * @param origin  AGENCY_ACCOUNT, CREDITOR_DIRECT, CREDITOR_REPORT ou OTHER
     */
    record ExternalPaymentCommand(UUID creditorId, UUID debtorId, BigDecimal amount, Instant paidAt, String method,
                                  String origin, String externalReference, String note, TargetInput target) {
    }

    record EvidenceUpload(String fileName, String contentType, long size, InputStream content) {
    }

    record EvidenceContent(String fileName, String contentType, long size, InputStream content) {
    }

    /** @param reason PROVIDER_REFUND, CHARGEBACK, BANK_RETURN, REGISTRATION_ERROR, FRAUD ou OTHER */
    record ReverseCommand(String reason, String note) {
    }

    /**
     * @param sourceAllocationId alocação a acordo com valor não aplicado; nulo = saldo não alocado do pagamento
     * @param target             novo destino; nulo com {@code refundReference} = devolução ao pagador
     */
    record ReallocateCommand(UUID sourceAllocationId, BigDecimal amount, TargetInput target, String refundReference,
                             String note) {
    }

    /**
     * @param status      INFORMED, CONFIRMED, RECONCILED, REJECTED ou REVERSED
     * @param agreementId pagamentos do acordo: destino solicitado do externo (inclusive pendentes e
     *                    recusados) ou alguma alocação a ele (integrados, realocações)
     */
    record PaymentFilter(UUID creditorId, UUID debtorId, String status, String source, String method, Instant paidFrom,
                         Instant paidTo, Boolean withBalance, UUID agreementId) {

        public PaymentFilter(UUID creditorId, UUID debtorId, String status, String source, String method,
                             Instant paidFrom, Instant paidTo, Boolean withBalance) {
            this(creditorId, debtorId, status, source, method, paidFrom, paidTo, withBalance, null);
        }
    }

    record LineView(UUID installmentId, UUID titleId, BigDecimal amount) {
    }

    /**
     * @param id       unidade de baixa: é o {@code paymentId} publicado em {@code PaymentConfirmed}
     * @param outcome  PENDING, APPLIED, PARTIAL, UNAPPLIED (resultado no negotiation) ou NOT_APPLICABLE
     */
    record AllocationView(UUID id, String kind, String origin, String status, UUID agreementId, UUID portfolioId,
                          BigDecimal amount, BigDecimal releasedAmount, String outcome, BigDecimal appliedAmount,
                          BigDecimal unappliedAmount, BigDecimal availableForReallocation, UUID sourceAllocationId,
                          String refundReference, List<LineView> lines, Instant createdAt, Instant reversedAt) {
    }

    record ReversalView(UUID id, BigDecimal amount, String reason, String source, String note, Instant reversedAt,
                        UUID reversedBy) {
    }

    /**
     * Destino solicitado no registro de um pagamento externo (o que o operador informou), para quem aprova
     * ver para onde o valor vai antes de a confirmação gerar as alocações.
     *
     * @param kind      AGREEMENT (parcelas de um acordo) ou TITLES (títulos avulsos)
     * @param itemIds   parcelas (AGREEMENT) ou títulos (TITLES) em ordem de preferência; vazio no acordo = todas
     *                  as parcelas em aberto
     * @param strategy  títulos: OLDEST_FIRST ou PROPORTIONAL; nulo no acordo
     */
    record RequestedTargetView(String kind, UUID agreementId, List<UUID> itemIds, String strategy) {

        public RequestedTargetView {
            itemIds = itemIds == null ? List.of() : List.copyOf(itemIds);
        }
    }

    /**
     * @param unallocatedAmount        parte do pagamento sem destino (sobra)
     * @param pendingReallocation      sobra + não aplicado pelo negotiation ainda não realocado
     * @param reviewThresholdExceeded  externo acima do limite: aprovação por segunda pessoa
     * @param requestedTarget          destino solicitado do pagamento externo (nulo nos integrados)
     * @param breakSuspension          externo em revisão com acordo de destino: suspensão da quebra mais recente do
     *                                 acordo (só na fila de revisão e no detalhe, para quem tem {@code AGREEMENT_VIEW})
     */
    record PaymentView(UUID id, String source, String method, String status, UUID creditorId, UUID debtorId,
                       BigDecimal amount, Instant paidAt, UUID instructionId, String providerTransactionId,
                       String externalReference, String externalOrigin, String note, UUID informedBy, Instant informedAt,
                       boolean reviewThresholdExceeded, String reviewMode, UUID reviewedBy, Instant reviewedAt,
                       String reviewNote, Instant confirmedAt, Instant reconciledAt, String reconciliationMode,
                       Instant reversedAt, BigDecimal allocatedAmount, BigDecimal unallocatedAmount,
                       BigDecimal pendingReallocation, boolean evidenceAvailable, String evidenceSha256,
                       List<AllocationView> allocations, ReversalView reversal, RequestedTargetView requestedTarget,
                       BreakSuspensionView breakSuspension, long version) {

        /**
         * Com a suspensão da quebra do acordo de destino (pagamento em revisão: destaque na fila quando o
         * prazo máximo está perto ou já venceu — decisão 546).
         */
        public PaymentView withBreakSuspension(BreakSuspensionView suspension) {
            return new PaymentView(id, source, method, status, creditorId, debtorId, amount, paidAt, instructionId,
                    providerTransactionId, externalReference, externalOrigin, note, informedBy, informedAt,
                    reviewThresholdExceeded, reviewMode, reviewedBy, reviewedAt, reviewNote, confirmedAt, reconciledAt,
                    reconciliationMode, reversedAt, allocatedAmount, unallocatedAmount, pendingReallocation,
                    evidenceAvailable, evidenceSha256, allocations, reversal, requestedTarget, suspension, version);
        }
    }
}
