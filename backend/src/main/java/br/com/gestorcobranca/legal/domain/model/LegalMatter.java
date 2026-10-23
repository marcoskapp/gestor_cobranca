package br.com.gestorcobranca.legal.domain.model;

import br.com.gestorcobranca.shared.domain.ConflictException;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Caso jurídico (docs/design/juridico.md §B.1): títulos de um caso de cobrança (devedor ×
 * carteira) indicados ao jurídico, com fase (§B.2), via principal, responsável (jurídico interno
 * ou escritório parceiro) e valores (dívida, custas previstas, valor recuperável estimado,
 * despesas lançadas e valores recuperados). Há no máximo um caso jurídico não final por caso de
 * cobrança (índice único parcial no banco).
 * <p>
 * As transições validam a tabela de fases e as pré-condições de cada fase
 * ({@link StageRequirements}): viabilidade antes da autorização, autorização do credor antes das
 * medidas e do ajuizamento, processo cadastrado para FILED, acordo para SETTLED_IN_COURT e motivo
 * para CLOSED.
 */
@Entity
@Table(name = "legal_matter", schema = "legal")
public class LegalMatter extends TenantScopedEntity {

    public static final int REASON_MAX = 500;

    @Column(name = "collection_case_id", nullable = false, updatable = false)
    private UUID collectionCaseId;

    @Column(name = "debtor_id", nullable = false, updatable = false)
    private UUID debtorId;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "portfolio_id", nullable = false, updatable = false)
    private UUID portfolioId;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, length = 20, updatable = false)
    private MatterOrigin origin;

    @Column(name = "eligibility_rule_id", updatable = false)
    private UUID eligibilityRuleId;

    @Column(name = "referral_note", length = REASON_MAX, updatable = false)
    private String referralNote;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "legal_matter_title", schema = "legal", joinColumns = @JoinColumn(name = "matter_id"))
    @Column(name = "title_id", nullable = false)
    private Set<UUID> titleIds = new HashSet<>();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "legal_matter_flag", schema = "legal", joinColumns = @JoinColumn(name = "matter_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "flag", nullable = false, length = 40)
    private Set<MatterFlag> flags = EnumSet.noneOf(MatterFlag.class);

    @Enumerated(EnumType.STRING)
    @Column(name = "stage", nullable = false, length = 30)
    private LegalStage stage;

    @Enumerated(EnumType.STRING)
    @Column(name = "resume_stage", length = 30)
    private LegalStage resumeStage;

    @Column(name = "stage_reason", length = REASON_MAX)
    private String stageReason;

    @Column(name = "stage_changed_at", nullable = false)
    private Instant stageChangedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "primary_route", length = 40)
    private LegalRoute primaryRoute;

    @Enumerated(EnumType.STRING)
    @Column(name = "responsible_type", nullable = false, length = 20)
    private ResponsibleType responsibleType;

    @Column(name = "responsible_user_id")
    private UUID responsibleUserId;

    @Column(name = "law_firm_id")
    private UUID lawFirmId;

    @Column(name = "debt_amount", nullable = false, precision = 19, scale = 2)
    private Money debtAmount;

    @Column(name = "expected_costs", precision = 19, scale = 2)
    private Money expectedCosts;

    @Column(name = "estimated_recoverable", precision = 19, scale = 2)
    private Money estimatedRecoverable;

    @Column(name = "authorization_id")
    private UUID authorizationId;

    @Column(name = "authorized_budget", precision = 19, scale = 2)
    private Money authorizedBudget;

    @Column(name = "expenses_total", nullable = false, precision = 19, scale = 2)
    private Money expensesTotal;

    @Column(name = "recovered_amount", nullable = false, precision = 19, scale = 2)
    private Money recoveredAmount;

    @Column(name = "agreement_id")
    private UUID agreementId;

    @Column(name = "collection_hold", nullable = false)
    private boolean collectionHold;

    @Column(name = "referred_at", nullable = false, updatable = false)
    private Instant referredAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "closure_reason", length = 30)
    private ClosureReason closureReason;

    protected LegalMatter() {
    }

    public LegalMatter(UUID collectionCaseId, UUID debtorId, UUID creditorId, UUID portfolioId,
                       Collection<UUID> titleIds, MatterOrigin origin, UUID eligibilityRuleId, String referralNote,
                       Money debtAmount, Set<MatterFlag> flags, Instant now) {
        Guard.require(collectionCaseId != null, "legal_matter.case_required", "Caso de cobrança obrigatório");
        Guard.require(debtorId != null, "legal_matter.debtor_required", "Devedor obrigatório");
        Guard.require(creditorId != null, "legal_matter.creditor_required", "Credor obrigatório");
        Guard.require(portfolioId != null, "legal_matter.portfolio_required", "Carteira obrigatória");
        Guard.require(titleIds != null && !titleIds.isEmpty(), "legal_matter.titles_required",
                "Ao menos um título em cobrança é necessário");
        Guard.require(origin != null, "legal_matter.origin_required", "Origem obrigatória");
        Guard.require(origin != MatterOrigin.MANUAL || (referralNote != null && !referralNote.isBlank()),
                "legal_matter.reason_required", "Indicação manual exige motivo");
        Guard.require(debtAmount != null && debtAmount.isPositive(), "legal_matter.debt_amount_invalid",
                "Valor da dívida deve ser positivo");
        this.collectionCaseId = collectionCaseId;
        this.debtorId = debtorId;
        this.creditorId = creditorId;
        this.portfolioId = portfolioId;
        this.titleIds = new HashSet<>(titleIds);
        this.origin = origin;
        this.eligibilityRuleId = eligibilityRuleId;
        this.referralNote = reason(referralNote, false);
        this.debtAmount = debtAmount;
        this.flags = flags == null || flags.isEmpty() ? EnumSet.noneOf(MatterFlag.class) : EnumSet.copyOf(flags);
        this.stage = LegalStage.ELIGIBLE;
        this.stageChangedAt = now;
        this.referredAt = now;
        this.responsibleType = ResponsibleType.NONE;
        this.expensesTotal = Money.ZERO;
        this.recoveredAmount = Money.ZERO;
    }

    // ------------------------------------------------------------------ fases

    /**
     * Move o caso para a fase indicada, validando a tabela de transições e as pré-condições.
     *
     * @return a fase anterior
     */
    public LegalStage moveTo(LegalStage target, StageRequirements req, String reason, Instant now) {
        Guard.require(target != null, "legal_matter.stage_required", "Fase obrigatória");
        String why = reason(reason, true);
        if (!stage.canMoveTo(target)) {
            throw new ConflictException("legal_matter.transition_invalid",
                    "Transição de %s para %s não permitida".formatted(stage, target));
        }
        switch (target) {
            case AWAITING_AUTHORIZATION -> Guard.requireState(req.viabilityCompleted() && Boolean.TRUE.equals(req.viable()),
                    "legal_matter.viability_required", "Registre a análise de viabilidade com conclusão favorável");
            case NOT_VIABLE -> Guard.requireState(req.viabilityCompleted(), "legal_matter.viability_required",
                    "Registre a análise de viabilidade antes de concluir pela inviabilidade");
            case EXTRAJUDICIAL -> Guard.requireState(req.authorizedExtrajudicial(), "legal_matter.authorization_required",
                    "O credor ainda não autorizou medida extrajudicial");
            case FILED -> {
                Guard.requireState(req.authorizedJudicial(), "legal_matter.authorization_required",
                        "O credor ainda não autorizou o ajuizamento");
                Guard.requireState(req.lawsuitRegistered(), "legal_matter.lawsuit_required",
                        "Cadastre o processo (número CNJ) antes de marcar o caso como ajuizado");
            }
            case SETTLED_IN_COURT -> {
                Guard.requireState(req.agreementId() != null, "legal_matter.agreement_required",
                        "Informe o acordo judicial");
                this.resumeStage = stage;
                this.agreementId = req.agreementId();
            }
            case CLOSED -> {
                Guard.require(req.closureReason() != null, "legal_matter.closure_reason_required",
                        "Motivo do encerramento obrigatório");
                this.closureReason = req.closureReason();
                this.closedAt = now;
            }
            case RETURNED_TO_CREDITOR -> this.closedAt = now;
            default -> {
                // ELIGIBLE não é destino; UNDER_REVIEW/IN_PROGRESS/ENFORCEMENT só dependem da tabela
            }
        }
        if (target != LegalStage.SETTLED_IN_COURT && target != LegalStage.CLOSED) {
            this.resumeStage = null;
        }
        LegalStage previous = stage;
        this.stage = target;
        this.stageReason = why;
        this.stageChangedAt = now;
        return previous;
    }

    /**
     * Quebra ou cancelamento do acordo judicial: o processo retoma a fase guardada.
     *
     * @return a fase anterior (SETTLED_IN_COURT)
     */
    public LegalStage resumeAfterAgreement(String reason, Instant now) {
        Guard.requireState(stage == LegalStage.SETTLED_IN_COURT && resumeStage != null, "legal_matter.not_settled",
                "O caso jurídico não está em acordo judicial");
        LegalStage previous = stage;
        this.stage = resumeStage;
        this.resumeStage = null;
        this.agreementId = null;
        this.stageReason = reason(reason, true);
        this.stageChangedAt = now;
        return previous;
    }

    public void requireOpen() {
        Guard.requireState(!stage.isTerminal(), "legal_matter.closed", "Caso jurídico encerrado");
    }

    // ------------------------------------------------------------------ responsável

    /** @return true se mudou */
    public boolean assignInternal(UUID userId) {
        requireOpen();
        Guard.require(userId != null, "legal_matter.responsible_required", "Responsável obrigatório");
        boolean changed = responsibleType != ResponsibleType.INTERNAL || !userId.equals(responsibleUserId);
        this.responsibleType = ResponsibleType.INTERNAL;
        this.responsibleUserId = userId;
        this.lawFirmId = null;
        return changed;
    }

    /** @param memberUserId advogado do escritório responsável pelo caso (opcional) */
    public boolean assignLawFirm(UUID firmId, UUID memberUserId) {
        requireOpen();
        Guard.require(firmId != null, "legal_matter.law_firm_required", "Escritório obrigatório");
        boolean changed = responsibleType != ResponsibleType.LAW_FIRM || !firmId.equals(lawFirmId)
                || !Objects.equals(memberUserId, responsibleUserId);
        this.responsibleType = ResponsibleType.LAW_FIRM;
        this.lawFirmId = firmId;
        this.responsibleUserId = memberUserId;
        return changed;
    }

    // ------------------------------------------------------------------ valores e vínculos

    public void applyViability(LegalRoute route, Money debt, Money costs, Money recoverable) {
        requireOpen();
        this.primaryRoute = route;
        if (debt != null && debt.isPositive()) {
            this.debtAmount = debt;
        }
        this.expectedCosts = costs;
        this.estimatedRecoverable = recoverable;
    }

    public void applyAuthorization(UUID authorizationId, Money budget) {
        requireOpen();
        this.authorizationId = authorizationId;
        this.authorizedBudget = budget;
    }

    public void addExpense(Money amount) {
        this.expensesTotal = expensesTotal.plus(amount);
    }

    public void removeExpense(Money amount) {
        this.expensesTotal = expensesTotal.minus(amount);
    }

    public void addRecovered(Money amount) {
        Guard.require(amount != null && amount.isPositive(), "legal_matter.recovered_invalid",
                "Valor recuperado deve ser positivo");
        this.recoveredAmount = recoveredAmount.plus(amount);
    }

    /** Acordo extrajudicial (fases anteriores ao ajuizamento) acompanhado pelo caso jurídico. */
    public void trackAgreement(UUID agreementId) {
        this.agreementId = agreementId;
    }

    public void clearAgreement(UUID agreementId) {
        if (Objects.equals(this.agreementId, agreementId) && stage != LegalStage.SETTLED_IN_COURT) {
            this.agreementId = null;
        }
    }

    /** Renegociação: o novo acordo substitui o anterior no vínculo. */
    public void replaceAgreement(UUID previousId, UUID newId) {
        if (Objects.equals(this.agreementId, previousId)) {
            this.agreementId = newId;
        }
    }

    /** @return true se a sinalização foi incluída agora */
    public boolean flag(MatterFlag flag) {
        return flags.add(flag);
    }

    public void markCollectionHold(boolean hold) {
        this.collectionHold = hold;
    }

    public boolean covers(UUID titleId) {
        return titleIds.contains(titleId);
    }

    private static String reason(String text, boolean required) {
        String value = text == null || text.isBlank() ? null : text.trim();
        Guard.require(!required || value != null, "legal_matter.reason_required", "Motivo obrigatório");
        Guard.require(value == null || value.length() <= REASON_MAX, "legal_matter.reason_too_long",
                "Motivo deve ter até %d caracteres".formatted(REASON_MAX));
        return value;
    }

    // ------------------------------------------------------------------ getters

    public UUID getCollectionCaseId() {
        return collectionCaseId;
    }

    public UUID getDebtorId() {
        return debtorId;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public UUID getPortfolioId() {
        return portfolioId;
    }

    public MatterOrigin getOrigin() {
        return origin;
    }

    public UUID getEligibilityRuleId() {
        return eligibilityRuleId;
    }

    public String getReferralNote() {
        return referralNote;
    }

    public Set<UUID> getTitleIds() {
        return Set.copyOf(titleIds);
    }

    public Set<MatterFlag> getFlags() {
        return flags.isEmpty() ? Set.of() : Set.copyOf(flags);
    }

    public LegalStage getStage() {
        return stage;
    }

    public LegalStage getResumeStage() {
        return resumeStage;
    }

    public String getStageReason() {
        return stageReason;
    }

    public Instant getStageChangedAt() {
        return stageChangedAt;
    }

    public LegalRoute getPrimaryRoute() {
        return primaryRoute;
    }

    public ResponsibleType getResponsibleType() {
        return responsibleType;
    }

    public UUID getResponsibleUserId() {
        return responsibleUserId;
    }

    public UUID getLawFirmId() {
        return lawFirmId;
    }

    public Money getDebtAmount() {
        return debtAmount;
    }

    public Money getExpectedCosts() {
        return expectedCosts;
    }

    public Money getEstimatedRecoverable() {
        return estimatedRecoverable;
    }

    public UUID getAuthorizationId() {
        return authorizationId;
    }

    public Money getAuthorizedBudget() {
        return authorizedBudget;
    }

    public Money getExpensesTotal() {
        return expensesTotal;
    }

    public Money getRecoveredAmount() {
        return recoveredAmount;
    }

    public UUID getAgreementId() {
        return agreementId;
    }

    public boolean isCollectionHold() {
        return collectionHold;
    }

    public Instant getReferredAt() {
        return referredAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public ClosureReason getClosureReason() {
        return closureReason;
    }
}
