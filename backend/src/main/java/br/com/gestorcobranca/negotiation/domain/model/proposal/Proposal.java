package br.com.gestorcobranca.negotiation.domain.model.proposal;

import br.com.gestorcobranca.negotiation.domain.model.agreement.AcceptanceEvidence;
import br.com.gestorcobranca.negotiation.domain.model.simulation.ConcessionReason;
import br.com.gestorcobranca.shared.crypto.EncryptedStringConverter;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.Percentage;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Proposta de acordo (§5) criada a partir de uma simulação: condições (nível, descontos, forma de
 * pagamento), resultado da validação do {@code NegotiationGuard} com o perfil do proponente,
 * memória de cálculo, aprovação (F16), evidência de aceite e o vínculo com o acordo formalizado.
 * Registra ator (tipo e id), versão de política, nível, canal e — quando renegociação ou handoff —
 * o acordo substituído e a sugestão do assistente.
 */
@Entity
@Table(name = "proposal", schema = "negotiation")
public class Proposal extends TenantScopedEntity {

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;
    @Column(name = "debtor_id", nullable = false, updatable = false)
    private UUID debtorId;
    @Column(name = "portfolio_id", nullable = false, updatable = false)
    private UUID portfolioId;
    @Column(name = "case_id", updatable = false)
    private UUID caseId;
    @Column(name = "team_id", updatable = false)
    private UUID teamId;
    @Column(name = "simulation_id", nullable = false, updatable = false)
    private UUID simulationId;
    @Column(name = "policy_id", nullable = false, updatable = false)
    private UUID policyId;
    @Column(name = "policy_version", nullable = false, updatable = false)
    private int policyVersion;
    @Column(name = "override_code", updatable = false, length = 40)
    private String overrideCode;
    @Column(name = "reference_date", nullable = false, updatable = false)
    private LocalDate referenceDate;
    @Column(name = "actor_type", nullable = false, updatable = false, length = 20)
    private String actorType;
    @Column(name = "actor_id", nullable = false, updatable = false)
    private UUID actorId;
    @Column(name = "authority_profile_code", nullable = false, updatable = false, length = 40)
    private String authorityProfileCode;
    @Column(name = "concession_level", nullable = false, updatable = false)
    private int level;
    @Enumerated(EnumType.STRING)
    @Column(name = "justification_reason", updatable = false, length = 30)
    private ConcessionReason justificationReason;
    @Column(name = "justification_note", length = 500)
    private String justificationNote;
    @Column(name = "copilot", nullable = false, updatable = false)
    private boolean copilot;
    @Column(name = "channel", updatable = false, length = 20)
    private String channel;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "proposal_title", schema = "negotiation", joinColumns = @JoinColumn(name = "proposal_id"))
    @OrderColumn(name = "position")
    @Column(name = "title_id", nullable = false)
    private List<UUID> titleIds = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "discount_rates", nullable = false, updatable = false)
    private String discountRates;
    @Column(name = "installments", nullable = false, updatable = false)
    private int installments;
    @Column(name = "requested_down_payment", updatable = false, precision = 19, scale = 2)
    private Money requestedDownPayment;
    @Column(name = "first_due_date", nullable = false, updatable = false)
    private LocalDate firstDueDate;
    @Column(name = "updated_amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private Money updatedAmount;
    @Column(name = "discount_amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private Money discountAmount;
    @Column(name = "negotiated_amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private Money negotiatedAmount;
    @Column(name = "down_payment", nullable = false, updatable = false, precision = 19, scale = 2)
    private Money downPayment;
    @Column(name = "financing_interest", nullable = false, updatable = false, precision = 19, scale = 2)
    private Money financingInterest;
    @Column(name = "total_amount", nullable = false, updatable = false, precision = 19, scale = 2)
    private Money totalAmount;
    @Column(name = "envelope_usage", nullable = false, updatable = false, precision = 9, scale = 4)
    private Percentage envelopeUsage;
    @Column(name = "max_days_overdue", nullable = false, updatable = false)
    private long maxDaysOverdue;
    @Column(name = "terms_fingerprint", nullable = false, updatable = false, length = 64)
    private String termsFingerprint;

    @Column(name = "required_discount_share", nullable = false, updatable = false, precision = 9, scale = 4)
    private Percentage requiredDiscountShare;
    @Column(name = "down_payment_share", nullable = false, updatable = false, precision = 9, scale = 4)
    private Percentage downPaymentShare;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "authority_violations", nullable = false, updatable = false)
    private String authorityViolations;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "calculation", nullable = false, updatable = false)
    private String calculation;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ProposalStatus status;
    @Column(name = "valid_until", nullable = false, updatable = false)
    private LocalDate validUntil;

    @Column(name = "replaced_agreement_id", updatable = false)
    private UUID replacedAgreementId;
    @Column(name = "renegotiation_number", nullable = false, updatable = false)
    private int renegotiationNumber;
    @Column(name = "handoff_proposal_id", updatable = false)
    private UUID handoffProposalId;

    @Column(name = "decided_by")
    private UUID decidedBy;
    @Column(name = "decided_by_profile", length = 40)
    private String decidedByProfile;
    @Column(name = "decided_at")
    private Instant decidedAt;
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "decision_note_encrypted", length = 8000)
    private String decisionNote;

    @Embedded
    private AcceptanceEvidence acceptance;

    @Column(name = "agreement_id")
    private UUID agreementId;
    @Column(name = "formalized_at")
    private Instant formalizedAt;
    @Column(name = "expired_at")
    private Instant expiredAt;

    protected Proposal() {
    }

    /** Condições e resultado do cálculo validado na criação. */
    public record Draft(UUID creditorId, UUID debtorId, UUID portfolioId, UUID caseId, UUID teamId, UUID simulationId,
                        UUID policyId, int policyVersion, String overrideCode, LocalDate referenceDate, String actorType,
                        UUID actorId, String authorityProfileCode, int level, ConcessionReason justificationReason,
                        String justificationNote, boolean copilot, String channel, List<UUID> titleIds,
                        String discountRatesJson, int installments, Money requestedDownPayment, LocalDate firstDueDate,
                        Money updatedAmount, Money discountAmount, Money negotiatedAmount, Money downPayment,
                        Money financingInterest, Money totalAmount, BigDecimal envelopeUsage, long maxDaysOverdue,
                        String termsFingerprint, BigDecimal requiredDiscountShare, BigDecimal downPaymentShare,
                        String authorityViolationsJson, String calculationJson, LocalDate validUntil,
                        UUID replacedAgreementId, int renegotiationNumber, UUID handoffProposalId) {
    }

    /**
     * @param initial DRAFT (dentro da alçada), PENDING_APPROVAL (humano acima da alçada) ou
     *                HANDED_OFF (assistente acima da alçada)
     */
    public Proposal(Draft d, ProposalStatus initial) {
        Guard.require(initial == ProposalStatus.DRAFT || initial == ProposalStatus.PENDING_APPROVAL
                || initial == ProposalStatus.HANDED_OFF, "proposal.invalid", "Situação inicial inválida");
        Guard.require(d.creditorId() != null && d.debtorId() != null && d.portfolioId() != null && d.simulationId() != null
                && d.policyId() != null, "proposal.invalid", "Credor, devedor, carteira, simulação e política obrigatórios");
        Guard.require(d.titleIds() != null && !d.titleIds().isEmpty(), "proposal.invalid", "Títulos obrigatórios");
        Guard.require(d.level() == 0 || d.justificationReason() != null, "proposal.justification_required",
                "Nível acima de 0 exige justificativa");
        Guard.require(d.justificationNote() == null || d.justificationNote().length() <= 500, "proposal.note_too_long",
                "Observação até 500 caracteres");
        Guard.require(d.validUntil() != null, "proposal.invalid", "Validade obrigatória");
        Guard.require(d.renegotiationNumber() == 0 || d.replacedAgreementId() != null, "proposal.invalid",
                "Renegociação exige o acordo substituído");
        this.creditorId = d.creditorId();
        this.debtorId = d.debtorId();
        this.portfolioId = d.portfolioId();
        this.caseId = d.caseId();
        this.teamId = d.teamId();
        this.simulationId = d.simulationId();
        this.policyId = d.policyId();
        this.policyVersion = d.policyVersion();
        this.overrideCode = d.overrideCode();
        this.referenceDate = d.referenceDate();
        this.actorType = d.actorType();
        this.actorId = d.actorId();
        this.authorityProfileCode = d.authorityProfileCode();
        this.level = d.level();
        this.justificationReason = d.justificationReason();
        this.justificationNote = d.justificationNote();
        this.copilot = d.copilot();
        this.channel = d.channel();
        this.titleIds.addAll(d.titleIds());
        this.discountRates = d.discountRatesJson();
        this.installments = d.installments();
        this.requestedDownPayment = d.requestedDownPayment();
        this.firstDueDate = d.firstDueDate();
        this.updatedAmount = d.updatedAmount();
        this.discountAmount = d.discountAmount();
        this.negotiatedAmount = d.negotiatedAmount();
        this.downPayment = d.downPayment();
        this.financingInterest = d.financingInterest();
        this.totalAmount = d.totalAmount();
        this.envelopeUsage = Percentage.of(d.envelopeUsage());
        this.maxDaysOverdue = d.maxDaysOverdue();
        this.termsFingerprint = d.termsFingerprint();
        this.requiredDiscountShare = Percentage.of(d.requiredDiscountShare());
        this.downPaymentShare = Percentage.of(d.downPaymentShare());
        this.authorityViolations = d.authorityViolationsJson();
        this.calculation = d.calculationJson();
        this.validUntil = d.validUntil();
        this.replacedAgreementId = d.replacedAgreementId();
        this.renegotiationNumber = d.renegotiationNumber();
        this.handoffProposalId = d.handoffProposalId();
        this.status = initial;
    }

    /** Validade vencida: após {@code validUntil} (que nunca passa do 1º vencimento). */
    public boolean isExpiredOn(LocalDate today) {
        return validUntil.isBefore(today);
    }

    public void approve(UUID approverId, String approverProfile, String note, Instant at) {
        Guard.requireState(status == ProposalStatus.PENDING_APPROVAL, "proposal.not_pending",
                "A proposta não está aguardando aprovação");
        decide(approverId, approverProfile, note, at);
        status = ProposalStatus.APPROVED;
    }

    public void reject(UUID approverId, String approverProfile, String reason, Instant at) {
        Guard.requireState(status == ProposalStatus.PENDING_APPROVAL, "proposal.not_pending",
                "A proposta não está aguardando aprovação");
        Guard.require(reason != null && !reason.isBlank(), "proposal.rejection_reason_required",
                "Informe o motivo da recusa");
        decide(approverId, approverProfile, reason, at);
        status = ProposalStatus.REJECTED;
    }

    private void decide(UUID approverId, String approverProfile, String note, Instant at) {
        Guard.require(approverId != null, "proposal.approver_required", "Aprovador obrigatório");
        Guard.require(!("USER".equals(actorType) && approverId.equals(actorId)), "proposal.self_approval",
                "Quem propôs não decide a própria proposta");
        Guard.require(note == null || note.length() <= 1000, "proposal.decision_note_too_long",
                "Observação da decisão até 1000 caracteres");
        this.decidedBy = approverId;
        this.decidedByProfile = approverProfile;
        this.decidedAt = at;
        this.decisionNote = note == null || note.isBlank() ? null : note.trim();
    }

    /** Aceite do devedor (DRAFT dentro da alçada ou APPROVED), dentro da validade. */
    public void accept(AcceptanceEvidence evidence, LocalDate today) {
        Guard.requireState(status == ProposalStatus.DRAFT || status == ProposalStatus.APPROVED, "proposal.not_acceptable",
                "A proposta não pode ser aceita na situação " + status);
        Guard.requireState(!isExpiredOn(today), "proposal.expired", "A proposta está fora da validade");
        Guard.requireState(!firstDueDate.isBefore(today), "proposal.first_due_date_past",
                "O 1º vencimento já passou; refaça a proposta");
        this.acceptance = evidence;
        this.status = ProposalStatus.ACCEPTED;
    }

    public void formalized(UUID agreementId, LocalDate today, Instant at) {
        Guard.requireState(status == ProposalStatus.ACCEPTED, "proposal.not_accepted",
                "A formalização exige o aceite do devedor");
        Guard.requireState(!isExpiredOn(today), "proposal.expired", "A proposta está fora da validade");
        this.agreementId = agreementId;
        this.formalizedAt = at;
        this.status = ProposalStatus.FORMALIZED;
    }

    public void expire(Instant at) {
        Guard.requireState(status.isOpen(), "proposal.not_open", "A proposta não está em aberto");
        this.status = ProposalStatus.EXPIRED;
        this.expiredAt = at;
    }

    /** Foi aprovada por alguém com alçada (a alçada do formalizador deixa de ser exigida). */
    public boolean wasApproved() {
        return decidedBy != null && (status == ProposalStatus.APPROVED || status == ProposalStatus.ACCEPTED
                || status == ProposalStatus.FORMALIZED);
    }

    /** LGPD: apaga textos livres e dados de rede do aceite. */
    public void anonymize() {
        this.justificationNote = null;
        this.decisionNote = null;
        if (acceptance != null) {
            acceptance = acceptance.withoutNetworkData();
        }
    }

    public UUID getCreditorId() { return creditorId; }
    public UUID getDebtorId() { return debtorId; }
    public UUID getPortfolioId() { return portfolioId; }
    public UUID getCaseId() { return caseId; }
    public UUID getTeamId() { return teamId; }
    public UUID getSimulationId() { return simulationId; }
    public UUID getPolicyId() { return policyId; }
    public int getPolicyVersion() { return policyVersion; }
    public String getOverrideCode() { return overrideCode; }
    public LocalDate getReferenceDate() { return referenceDate; }
    public String getActorType() { return actorType; }
    public UUID getActorId() { return actorId; }
    public String getAuthorityProfileCode() { return authorityProfileCode; }
    public int getLevel() { return level; }
    public ConcessionReason getJustificationReason() { return justificationReason; }
    public String getJustificationNote() { return justificationNote; }
    public boolean isCopilot() { return copilot; }
    public String getChannel() { return channel; }
    public List<UUID> getTitleIds() { return List.copyOf(titleIds); }
    public String getDiscountRates() { return discountRates; }
    public int getInstallments() { return installments; }
    public Money getRequestedDownPayment() { return requestedDownPayment; }
    public LocalDate getFirstDueDate() { return firstDueDate; }
    public Money getUpdatedAmount() { return updatedAmount; }
    public Money getDiscountAmount() { return discountAmount; }
    public Money getNegotiatedAmount() { return negotiatedAmount; }
    public Money getDownPayment() { return downPayment; }
    public Money getFinancingInterest() { return financingInterest; }
    public Money getTotalAmount() { return totalAmount; }
    public Percentage getEnvelopeUsage() { return envelopeUsage; }
    public long getMaxDaysOverdue() { return maxDaysOverdue; }
    public String getTermsFingerprint() { return termsFingerprint; }
    public Percentage getRequiredDiscountShare() { return requiredDiscountShare; }
    public Percentage getDownPaymentShare() { return downPaymentShare; }
    public String getAuthorityViolations() { return authorityViolations; }
    public String getCalculation() { return calculation; }
    public ProposalStatus getStatus() { return status; }
    public LocalDate getValidUntil() { return validUntil; }
    public UUID getReplacedAgreementId() { return replacedAgreementId; }
    public int getRenegotiationNumber() { return renegotiationNumber; }
    public UUID getHandoffProposalId() { return handoffProposalId; }
    public UUID getDecidedBy() { return decidedBy; }
    public String getDecidedByProfile() { return decidedByProfile; }
    public Instant getDecidedAt() { return decidedAt; }
    public String getDecisionNote() { return decisionNote; }
    public AcceptanceEvidence getAcceptance() { return acceptance; }
    public UUID getAgreementId() { return agreementId; }
    public Instant getFormalizedAt() { return formalizedAt; }
    public Instant getExpiredAt() { return expiredAt; }
}
