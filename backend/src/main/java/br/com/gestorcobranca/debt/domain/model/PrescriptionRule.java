package br.com.gestorcobranca.debt.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.Objects;

/**
 * Regra de prescrição do tenant para uma natureza de título: prazo em anos, data-base da
 * contagem e efeito das interrupções. Os prazos legais não ficam no código: cada assessoria
 * cadastra os que adota (com o fundamento) após validação jurídica. Sem regra para a natureza,
 * o título não tem data estimada de prescrição.
 */
@Entity
@Table(name = "prescription_rule", schema = "debt")
public class PrescriptionRule extends TenantScopedEntity {

    public static final int MAX_TERM_YEARS = 30;

    @Enumerated(EnumType.STRING)
    @Column(name = "instrument_type", nullable = false, length = 30, updatable = false)
    private InstrumentType instrumentType;

    @Column(name = "term_years", nullable = false)
    private int termYears;

    @Enumerated(EnumType.STRING)
    @Column(name = "base", nullable = false, length = 30)
    private PrescriptionBase base;

    @Enumerated(EnumType.STRING)
    @Column(name = "interruption_policy", nullable = false, length = 20)
    private InterruptionPolicy interruptionPolicy;

    @Column(name = "legal_basis", length = 300)
    private String legalBasis;

    protected PrescriptionRule() {
    }

    public PrescriptionRule(InstrumentType instrumentType, int termYears, PrescriptionBase base,
                            InterruptionPolicy interruptionPolicy, String legalBasis) {
        Guard.require(instrumentType != null, "prescription_rule.instrument_type_required", "Natureza do título obrigatória");
        this.instrumentType = instrumentType;
        update(termYears, base, interruptionPolicy, legalBasis);
    }

    /** @return true se algo mudou */
    public boolean update(int termYears, PrescriptionBase base, InterruptionPolicy interruptionPolicy, String legalBasis) {
        Guard.require(termYears >= 1 && termYears <= MAX_TERM_YEARS, "prescription_rule.term_invalid",
                "Prazo deve ser de 1 a %d anos".formatted(MAX_TERM_YEARS));
        Guard.require(base != null, "prescription_rule.base_required", "Data-base obrigatória");
        Guard.require(interruptionPolicy != null, "prescription_rule.interruption_policy_required",
                "Efeito das interrupções obrigatório");
        String basis = legalBasis == null || legalBasis.isBlank() ? null : legalBasis.trim();
        Guard.require(basis == null || basis.length() <= 300, "prescription_rule.legal_basis_too_long", "Fundamento muito longo");
        boolean changed = this.termYears != termYears || this.base != base || this.interruptionPolicy != interruptionPolicy
                || !Objects.equals(this.legalBasis, basis);
        this.termYears = termYears;
        this.base = base;
        this.interruptionPolicy = interruptionPolicy;
        this.legalBasis = basis;
        return changed;
    }

    public InstrumentType getInstrumentType() {
        return instrumentType;
    }

    public int getTermYears() {
        return termYears;
    }

    public PrescriptionBase getBase() {
        return base;
    }

    public InterruptionPolicy getInterruptionPolicy() {
        return interruptionPolicy;
    }

    public String getLegalBasis() {
        return legalBasis;
    }
}
