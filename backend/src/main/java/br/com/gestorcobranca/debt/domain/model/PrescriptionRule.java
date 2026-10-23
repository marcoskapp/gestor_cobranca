package br.com.gestorcobranca.debt.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Regra de prescrição do tenant para uma natureza de título e uma pretensão
 * ({@link PrescriptionClaim}): prazo em meses, data-base e deslocamento da contagem, efeito das
 * interrupções e janela de alerta. Os prazos legais não ficam no código: a assessoria cadastra
 * os que adota (com o fundamento) ou parte da tabela de mercado semeada, que fica marcada como
 * pendente de validação jurídica ({@link RuleReviewStatus#PENDING_LEGAL_REVIEW}) até ser
 * confirmada ou alterada por um responsável. Sem regra, o título não tem data estimada para a
 * pretensão.
 */
@Entity
@Table(name = "prescription_rule", schema = "debt")
public class PrescriptionRule extends TenantScopedEntity {

    public static final int MAX_TERM_MONTHS = 360;
    public static final int MAX_START_OFFSET_DAYS = 365;
    public static final int MAX_ALERT_DAYS = 730;
    private static final int LEGAL_BASIS_MAX = 300;

    @Enumerated(EnumType.STRING)
    @Column(name = "instrument_type", nullable = false, length = 30, updatable = false)
    private InstrumentType instrumentType;

    @Enumerated(EnumType.STRING)
    @Column(name = "claim", nullable = false, length = 20, updatable = false)
    private PrescriptionClaim claim;

    @Column(name = "term_months", nullable = false)
    private int termMonths;

    @Enumerated(EnumType.STRING)
    @Column(name = "base", nullable = false, length = 30)
    private PrescriptionBase base;

    @Column(name = "start_offset_days", nullable = false)
    private int startOffsetDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "interruption_policy", nullable = false, length = 20)
    private InterruptionPolicy interruptionPolicy;

    @Column(name = "alert_days", nullable = false)
    private int alertDays;

    @Column(name = "legal_basis", length = LEGAL_BASIS_MAX)
    private String legalBasis;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 30)
    private RuleReviewStatus reviewStatus;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    protected PrescriptionRule() {
    }

    /** Parâmetros ajustáveis da regra. */
    public record Terms(int termMonths, PrescriptionBase base, int startOffsetDays, InterruptionPolicy interruptionPolicy,
                        int alertDays, String legalBasis) {
    }

    /** Regra cadastrada por um responsável: nasce validada. */
    public static PrescriptionRule validated(InstrumentType instrumentType, PrescriptionClaim claim, Terms terms,
                                             UUID reviewer, Instant at) {
        PrescriptionRule rule = new PrescriptionRule(instrumentType, claim, terms);
        rule.markValidated(reviewer, at);
        return rule;
    }

    /** Regra copiada da tabela de mercado: aplicada, pendente de validação jurídica. */
    public static PrescriptionRule fromMarketTemplate(InstrumentType instrumentType, PrescriptionClaim claim, Terms terms) {
        PrescriptionRule rule = new PrescriptionRule(instrumentType, claim, terms);
        rule.reviewStatus = RuleReviewStatus.PENDING_LEGAL_REVIEW;
        return rule;
    }

    private PrescriptionRule(InstrumentType instrumentType, PrescriptionClaim claim, Terms terms) {
        Guard.require(instrumentType != null, "prescription_rule.instrument_type_required", "Natureza do título obrigatória");
        Guard.require(claim != null, "prescription_rule.claim_required", "Pretensão obrigatória");
        this.instrumentType = instrumentType;
        this.claim = claim;
        apply(terms);
    }

    /**
     * Altera os parâmetros. Quem altera assume a validação (a regra passa a VALIDATED).
     *
     * @return true se algum parâmetro mudou
     */
    public boolean update(Terms terms, UUID reviewer, Instant at) {
        boolean changed = apply(terms);
        if (changed || reviewStatus != RuleReviewStatus.VALIDATED) {
            markValidated(reviewer, at);
        }
        return changed;
    }

    /** Confirma a regra semeada sem alterá-la. @return true se estava pendente */
    public boolean validate(UUID reviewer, Instant at) {
        if (reviewStatus == RuleReviewStatus.VALIDATED) {
            return false;
        }
        markValidated(reviewer, at);
        return true;
    }

    /** Os parâmetros que afetam o cálculo mudariam com {@code terms} (a janela de alerta não afeta). */
    public boolean affectsCalculation(Terms terms) {
        return termMonths != terms.termMonths() || base != terms.base() || startOffsetDays != terms.startOffsetDays()
                || interruptionPolicy != terms.interruptionPolicy();
    }

    private void markValidated(UUID reviewer, Instant at) {
        this.reviewStatus = RuleReviewStatus.VALIDATED;
        this.reviewedBy = reviewer;
        this.reviewedAt = Objects.requireNonNull(at, "at");
    }

    private boolean apply(Terms terms) {
        Guard.require(terms != null, "prescription_rule.terms_required", "Parâmetros da regra obrigatórios");
        Guard.require(terms.termMonths() >= 1 && terms.termMonths() <= MAX_TERM_MONTHS, "prescription_rule.term_invalid",
                "Prazo deve ser de 1 a %d meses".formatted(MAX_TERM_MONTHS));
        Guard.require(terms.base() != null, "prescription_rule.base_required", "Data-base obrigatória");
        Guard.require(terms.startOffsetDays() >= 0 && terms.startOffsetDays() <= MAX_START_OFFSET_DAYS,
                "prescription_rule.start_offset_invalid",
                "Deslocamento da data-base deve ser de 0 a %d dias".formatted(MAX_START_OFFSET_DAYS));
        Guard.require(terms.interruptionPolicy() != null, "prescription_rule.interruption_policy_required",
                "Efeito das interrupções obrigatório");
        Guard.require(terms.alertDays() >= 1 && terms.alertDays() <= MAX_ALERT_DAYS, "prescription_rule.alert_days_invalid",
                "Janela de alerta deve ser de 1 a %d dias".formatted(MAX_ALERT_DAYS));
        String basis = terms.legalBasis() == null || terms.legalBasis().isBlank() ? null : terms.legalBasis().trim();
        Guard.require(basis == null || basis.length() <= LEGAL_BASIS_MAX, "prescription_rule.legal_basis_too_long",
                "Fundamento deve ter até %d caracteres".formatted(LEGAL_BASIS_MAX));
        boolean changed = termMonths != terms.termMonths() || base != terms.base()
                || startOffsetDays != terms.startOffsetDays() || interruptionPolicy != terms.interruptionPolicy()
                || alertDays != terms.alertDays() || !Objects.equals(legalBasis, basis);
        this.termMonths = terms.termMonths();
        this.base = terms.base();
        this.startOffsetDays = terms.startOffsetDays();
        this.interruptionPolicy = terms.interruptionPolicy();
        this.alertDays = terms.alertDays();
        this.legalBasis = basis;
        return changed;
    }

    public Terms terms() {
        return new Terms(termMonths, base, startOffsetDays, interruptionPolicy, alertDays, legalBasis);
    }

    public boolean isValidated() {
        return reviewStatus == RuleReviewStatus.VALIDATED;
    }

    public InstrumentType getInstrumentType() {
        return instrumentType;
    }

    public PrescriptionClaim getClaim() {
        return claim;
    }

    public int getTermMonths() {
        return termMonths;
    }

    public PrescriptionBase getBase() {
        return base;
    }

    public int getStartOffsetDays() {
        return startOffsetDays;
    }

    public InterruptionPolicy getInterruptionPolicy() {
        return interruptionPolicy;
    }

    public int getAlertDays() {
        return alertDays;
    }

    public String getLegalBasis() {
        return legalBasis;
    }

    public RuleReviewStatus getReviewStatus() {
        return reviewStatus;
    }

    public Instant getReviewedAt() {
        return reviewedAt;
    }

    public UUID getReviewedBy() {
        return reviewedBy;
    }
}
