package br.com.gestorcobranca.negotiation.application.port.in;

import br.com.gestorcobranca.negotiation.application.port.in.ProposalUseCases.AcceptanceView;
import br.com.gestorcobranca.negotiation.application.port.in.ProposalUseCases.TermContent;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Acordos (RB05): consulta ({@code AGREEMENT_VIEW}, escopo por credor), cancelamento
 * ({@code AGREEMENT_CANCEL}) e termo. A baixa de parcelas acontece pelo consumo de
 * {@code PaymentConfirmed}/{@code PaymentReversed}; a quebra, pelo job diário conforme a regra da
 * política fixada na formalização; a renegociação, por uma proposta com {@code replacedAgreementId}.
 */
public interface AgreementUseCases {

    AgreementView get(UUID agreementId);

    /** @param status FORMALIZED, IN_PAYMENT, SETTLED, BROKEN, CANCELLED, RENEGOTIATED */
    PageResponse<AgreementView> search(AgreementFilter filter, PageQuery page);

    List<PaymentView> payments(UUID agreementId);

    AgreementView cancel(UUID agreementId, String reason);

    /**
     * Distribuição de um pagamento aplicado ao acordo entre parcelas, títulos e componentes do razão
     * (insumo da prestação de contas ao credor — módulo {@code settlement}). Vazio se o pagamento não
     * foi registrado no acordo. {@code AGREEMENT_VIEW} e escopo por credor.
     *
     * @param paymentId o {@code paymentId} de {@code PaymentConfirmed} (id da alocação no payment)
     */
    Optional<PaymentDistributionView> paymentDistribution(UUID agreementId, UUID paymentId);

    /** Termo do acordo (PDF aceito pelo devedor). Registra {@code FILE_DOWNLOADED}. */
    TermContent openTerm(UUID agreementId);

    record AgreementFilter(UUID creditorId, UUID debtorId, UUID caseId, String status) {
    }

    record ParticipantView(String actorType, UUID actorId, String role) {
    }

    record InstallmentView(UUID id, int number, LocalDate dueDate, BigDecimal amount, BigDecimal paidAmount,
                           String status, Instant paidAt) {
    }

    /**
     * @param breakRule "N parcelas em atraso há mais de D dias" fixada na formalização
     * @param origin    HUMAN, HUMAN_COPILOT, ASSISTANT_HANDOFF_NEGOTIATED, ASSISTANT_HANDOFF_CONFIRMED,
     *                  ASSISTANT_AUTONOMOUS, PORTAL_SELF_SERVICE ou CAMPAIGN
     */
    record AgreementView(UUID id, String status, String statusReason, UUID proposalId, UUID creditorId, UUID debtorId,
                         UUID portfolioId, UUID caseId, UUID policyId, int policyVersion, int concessionLevel,
                         BigDecimal envelopeUsage, String channel, String origin, List<ParticipantView> participants,
                         BigDecimal updatedAmount, BigDecimal discountAmount, BigDecimal negotiatedAmount,
                         BigDecimal financingInterest, BigDecimal totalAmount, BigDecimal downPayment,
                         BigDecimal paidAmount, BigDecimal remainingAmount, List<UUID> titleIds,
                         List<InstallmentView> installments, BreakRuleView breakRule, Instant formalizedAt,
                         Instant inPaymentAt, Instant settledAt, Instant endedAt, UUID replacedAgreementId,
                         UUID replacedByAgreementId, int renegotiationNumber, BigDecimal incrementalAmount,
                         AcceptanceView acceptance, boolean termAvailable, long version) {
    }

    record BreakRuleView(int overdueInstallments, int toleranceDays) {
    }

    record InstallmentShareView(UUID installmentId, int number, BigDecimal amount) {
    }

    /**
     * @param titleReference identificação do título no credor ({@code externalId} ou {@code externalId/parcela})
     * @param component      PRINCIPAL, INTEREST, FINE, FEES ou HONORARIUM
     */
    record ComponentShareView(UUID titleId, String titleReference, String component, BigDecimal amount) {
    }

    /**
     * @param status           APPLIED, UNAPPLIED ou REVERSED (situação do pagamento no acordo)
     * @param installmentCount número de parcelas do acordo (1 = à vista)
     * @param maxDaysOverdue   maior atraso dos títulos do acordo na formalização
     */
    record PaymentDistributionView(UUID agreementId, UUID paymentId, UUID creditorId, UUID debtorId, UUID portfolioId,
                                   String status, BigDecimal amount, BigDecimal appliedAmount, Instant paidAt,
                                   String source, String method, int installmentCount, long maxDaysOverdue,
                                   int renegotiationNumber, UUID replacedAgreementId,
                                   List<InstallmentShareView> installments, List<ComponentShareView> components) {
    }

    /**
     * @param status       APPLIED, UNAPPLIED (excedente/acordo encerrado) ou REVERSED
     * @param ledgerStatus PENDING, POSTED, REVERSED ou SKIPPED (lançamento no razão do título)
     */
    record PaymentView(UUID paymentId, BigDecimal amount, BigDecimal appliedAmount, BigDecimal unappliedAmount,
                       Instant paidAt, String status, String ledgerStatus, Instant reversedAt) {
    }
}
