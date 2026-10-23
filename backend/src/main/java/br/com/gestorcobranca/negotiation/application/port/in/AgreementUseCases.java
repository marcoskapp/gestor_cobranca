package br.com.gestorcobranca.negotiation.application.port.in;

import br.com.gestorcobranca.negotiation.application.port.in.ProposalUseCases.AcceptanceView;
import br.com.gestorcobranca.negotiation.application.port.in.ProposalUseCases.TermContent;
import br.com.gestorcobranca.shared.paging.PageQuery;
import br.com.gestorcobranca.shared.paging.PageResponse;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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

    /**
     * @param status       APPLIED, UNAPPLIED (excedente/acordo encerrado) ou REVERSED
     * @param ledgerStatus PENDING, POSTED, REVERSED ou SKIPPED (lançamento no razão do título)
     */
    record PaymentView(UUID paymentId, BigDecimal amount, BigDecimal appliedAmount, BigDecimal unappliedAmount,
                       Instant paidAt, String status, String ledgerStatus, Instant reversedAt) {
    }
}
