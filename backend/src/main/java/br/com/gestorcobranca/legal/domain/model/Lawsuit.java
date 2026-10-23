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
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Processo judicial do caso jurídico (etapa 5): número CNJ validado (único na assessoria),
 * tribunal (derivado do número), órgão julgador, classe/via, polos (credor e devedores — ids, sem
 * dados pessoais), valor da causa, data de distribuição e situação. Andamentos, prazos e
 * audiências referenciam o processo.
 */
@Entity
@Table(name = "lawsuit", schema = "legal")
public class Lawsuit extends TenantScopedEntity {

    @Column(name = "matter_id", nullable = false, updatable = false)
    private UUID matterId;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "cnj_number", nullable = false, length = 20, updatable = false)
    private String cnjNumber;

    @Column(name = "court_code", nullable = false, length = 5, updatable = false)
    private String courtCode;

    @Column(name = "court_name", length = 150)
    private String courtName;

    @Column(name = "court_unit", length = 200)
    private String courtUnit;

    @Enumerated(EnumType.STRING)
    @Column(name = "route", nullable = false, length = 40, updatable = false)
    private LegalRoute route;

    @Column(name = "action_class", length = 150)
    private String actionClass;

    @Enumerated(EnumType.STRING)
    @Column(name = "creditor_pole", nullable = false, length = 10, updatable = false)
    private LitigationPole creditorPole;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "lawsuit_party", schema = "legal", joinColumns = @JoinColumn(name = "lawsuit_id"))
    @Column(name = "debtor_id", nullable = false)
    private Set<UUID> debtorIds = new HashSet<>();

    @Column(name = "claim_amount", nullable = false, precision = 19, scale = 2)
    private Money claimAmount;

    @Column(name = "filed_on", nullable = false, updatable = false)
    private LocalDate filedOn;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private LawsuitStatus status;

    @Column(name = "status_reason", length = 500)
    private String statusReason;

    @Column(name = "provider", nullable = false, length = 40)
    private String provider;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    protected Lawsuit() {
    }

    public Lawsuit(UUID matterId, UUID creditorId, CnjNumber cnj, LegalRoute route, String actionClass,
                   LitigationPole pole, Collection<UUID> debtorIds, Money claimAmount, LocalDate filedOn,
                   String courtName, String courtUnit, LocalDate today, String provider) {
        Guard.require(cnj != null, "lawsuit.cnj_required", "Número CNJ obrigatório");
        Guard.require(route != null && route.isJudicial(), "lawsuit.route_invalid", "Via judicial obrigatória");
        Guard.require(debtorIds != null && !debtorIds.isEmpty(), "lawsuit.parties_required",
                "Informe os devedores no polo contrário");
        Guard.require(claimAmount != null && claimAmount.isPositive(), "lawsuit.claim_amount_invalid",
                "Valor da causa deve ser positivo");
        Guard.require(filedOn != null && !filedOn.isAfter(today), "lawsuit.filed_on_invalid",
                "Data de distribuição obrigatória e não futura");
        Guard.require(cnj.year() == filedOn.getYear(), "lawsuit.cnj_year_mismatch",
                "O ano do número CNJ deve ser o ano da distribuição");
        this.matterId = matterId;
        this.creditorId = creditorId;
        this.cnjNumber = cnj.digits();
        this.courtCode = cnj.courtCode();
        this.route = route;
        this.actionClass = text(actionClass, 150);
        this.creditorPole = pole == null ? LitigationPole.ACTIVE : pole;
        this.debtorIds = new HashSet<>(debtorIds);
        this.claimAmount = claimAmount;
        this.filedOn = filedOn;
        this.courtName = text(courtName, 150);
        this.courtUnit = text(courtUnit, 200);
        this.status = LawsuitStatus.ACTIVE;
        this.provider = provider;
    }

    /** @return true se algo mudou */
    public boolean describe(String courtName, String courtUnit, String actionClass, Money claimAmount) {
        Guard.require(claimAmount == null || claimAmount.isPositive(), "lawsuit.claim_amount_invalid",
                "Valor da causa deve ser positivo");
        String name = text(courtName, 150);
        String unit = text(courtUnit, 200);
        String cls = text(actionClass, 150);
        Money amount = claimAmount == null ? this.claimAmount : claimAmount;
        boolean changed = !java.util.Objects.equals(name, this.courtName) || !java.util.Objects.equals(unit, this.courtUnit)
                || !java.util.Objects.equals(cls, this.actionClass) || !amount.equals(this.claimAmount);
        this.courtName = name;
        this.courtUnit = unit;
        this.actionClass = cls;
        this.claimAmount = amount;
        return changed;
    }

    public LawsuitStatus changeStatus(LawsuitStatus target, String reason) {
        Guard.require(target != null, "lawsuit.status_invalid", "Situação inválida");
        Guard.require(reason != null && !reason.isBlank(), "lawsuit.reason_required", "Motivo obrigatório");
        if (!status.canMoveTo(target)) {
            throw new ConflictException("lawsuit.transition_invalid",
                    "Processo em %s não pode passar a %s".formatted(status, target));
        }
        LawsuitStatus previous = status;
        this.status = target;
        this.statusReason = text(reason, 500);
        return previous;
    }

    public void markSynced(Instant at) {
        this.lastSyncedAt = at;
    }

    public CnjNumber cnj() {
        return new CnjNumber(cnjNumber);
    }

    private static String text(String value, int max) {
        String v = value == null || value.isBlank() ? null : value.trim();
        Guard.require(v == null || v.length() <= max, "lawsuit.text_too_long",
                "Texto deve ter até %d caracteres".formatted(max));
        return v;
    }

    public UUID getMatterId() {
        return matterId;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public String getCnjNumber() {
        return cnjNumber;
    }

    public String getCourtCode() {
        return courtCode;
    }

    public String getCourtName() {
        return courtName;
    }

    public String getCourtUnit() {
        return courtUnit;
    }

    public LegalRoute getRoute() {
        return route;
    }

    public String getActionClass() {
        return actionClass;
    }

    public LitigationPole getCreditorPole() {
        return creditorPole;
    }

    public Set<UUID> getDebtorIds() {
        return Set.copyOf(debtorIds);
    }

    public Money getClaimAmount() {
        return claimAmount;
    }

    public LocalDate getFiledOn() {
        return filedOn;
    }

    public LawsuitStatus getStatus() {
        return status;
    }

    public String getStatusReason() {
        return statusReason;
    }

    public String getProvider() {
        return provider;
    }

    public Instant getLastSyncedAt() {
        return lastSyncedAt;
    }
}
