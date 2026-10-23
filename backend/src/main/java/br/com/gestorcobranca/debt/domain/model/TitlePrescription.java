package br.com.gestorcobranca.debt.domain.model;

import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Prescrição estimada de uma pretensão de um título (no máximo uma por título e pretensão),
 * recalculada sempre que mudam a regra, a natureza do contrato, os vencimentos ou as
 * interrupções. Existe somente enquanto houver regra aplicável. É a base do alerta diário de
 * prescrição próxima, que registra para qual data estimada já alertou ({@code lastAlertedFor})
 * e só volta a alertar se a data estimada mudar.
 */
@Entity
@Table(name = "title_prescription", schema = "debt")
public class TitlePrescription extends TenantScopedEntity {

    @Column(name = "title_id", nullable = false, updatable = false)
    private UUID titleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "claim", nullable = false, length = 20, updatable = false)
    private PrescriptionClaim claim;

    @Column(name = "rule_id", nullable = false)
    private UUID ruleId;

    @Column(name = "base_date", nullable = false)
    private LocalDate baseDate;

    @Column(name = "counting_start", nullable = false)
    private LocalDate countingStart;

    @Column(name = "term_months", nullable = false)
    private int termMonths;

    @Column(name = "estimated_date", nullable = false)
    private LocalDate estimatedDate;

    @Column(name = "last_alerted_for")
    private LocalDate lastAlertedFor;

    @Column(name = "last_alerted_at")
    private Instant lastAlertedAt;

    protected TitlePrescription() {
    }

    public TitlePrescription(UUID titleId, PrescriptionCalculation calculation) {
        this.titleId = Objects.requireNonNull(titleId, "titleId");
        this.claim = calculation.claim();
        apply(calculation);
    }

    /** @return true se algo mudou */
    public boolean apply(PrescriptionCalculation c) {
        if (c.claim() != claim) {
            throw new IllegalArgumentException("Cálculo de outra pretensão");
        }
        boolean changed = !Objects.equals(baseDate, c.baseDate()) || !Objects.equals(countingStart, c.countingStart())
                || !Objects.equals(ruleId, c.ruleId()) || termMonths != c.termMonths()
                || !Objects.equals(estimatedDate, c.estimatedDate());
        this.baseDate = c.baseDate();
        this.countingStart = c.countingStart();
        this.ruleId = c.ruleId();
        this.termMonths = c.termMonths();
        this.estimatedDate = c.estimatedDate();
        return changed;
    }

    /** Data estimada dentro da janela {@code [today, today + windowDays]} e ainda não alertada. */
    public boolean needsAlert(LocalDate today, int windowDays) {
        return !estimatedDate.isBefore(today) && !estimatedDate.isAfter(today.plusDays(windowDays))
                && !estimatedDate.equals(lastAlertedFor);
    }

    public void markAlerted(Instant at) {
        this.lastAlertedFor = estimatedDate;
        this.lastAlertedAt = Objects.requireNonNull(at, "at");
    }

    public UUID getTitleId() {
        return titleId;
    }

    public PrescriptionClaim getClaim() {
        return claim;
    }

    public UUID getRuleId() {
        return ruleId;
    }

    public LocalDate getBaseDate() {
        return baseDate;
    }

    public LocalDate getCountingStart() {
        return countingStart;
    }

    public int getTermMonths() {
        return termMonths;
    }

    public LocalDate getEstimatedDate() {
        return estimatedDate;
    }

    public LocalDate getLastAlertedFor() {
        return lastAlertedFor;
    }

    public Instant getLastAlertedAt() {
        return lastAlertedAt;
    }
}
