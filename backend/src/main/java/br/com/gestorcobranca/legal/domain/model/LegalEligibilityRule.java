package br.com.gestorcobranca.legal.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Regra de indicação ao jurídico por credor (e, opcionalmente, carteira). Todos os critérios
 * informados precisam ser atendidos (E); regras diferentes se somam (OU). Critérios: atraso
 * mínimo, saldo mínimo, garantia, título executivo com pretensão executiva vigente, acordos
 * quebrados e prescrição próxima (dias até a data estimada mais próxima).
 */
@Entity
@Table(name = "eligibility_rule", schema = "legal")
public class LegalEligibilityRule extends TenantScopedEntity {

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "portfolio_id", updatable = false)
    private UUID portfolioId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "min_days_overdue")
    private Integer minDaysOverdue;

    @Column(name = "min_open_balance", precision = 19, scale = 2)
    private Money minOpenBalance;

    @Column(name = "require_collateral", nullable = false)
    private boolean requireCollateral;

    @Column(name = "require_executive_title", nullable = false)
    private boolean requireExecutiveTitle;

    @Column(name = "min_broken_agreements")
    private Integer minBrokenAgreements;

    @Column(name = "prescription_within_days")
    private Integer prescriptionWithinDays;

    protected LegalEligibilityRule() {
    }

    public LegalEligibilityRule(UUID creditorId, UUID portfolioId, Criteria criteria) {
        Guard.require(creditorId != null, "legal_rule.creditor_required", "Credor obrigatório");
        this.creditorId = creditorId;
        this.portfolioId = portfolioId;
        this.active = true;
        define(criteria);
    }

    /**
     * @param minDaysOverdue         atraso mínimo do título mais antigo (dias)
     * @param minOpenBalance         saldo mínimo dos títulos indicáveis
     * @param prescriptionWithinDays a data estimada de prescrição (vigente) mais próxima está a no máximo N dias
     */
    public record Criteria(String name, Integer minDaysOverdue, Money minOpenBalance, boolean requireCollateral,
                           boolean requireExecutiveTitle, Integer minBrokenAgreements, Integer prescriptionWithinDays) {
    }

    /** @return true se algo mudou */
    public boolean define(Criteria c) {
        Guard.require(c != null && c.name() != null && !c.name().isBlank() && c.name().trim().length() <= 120,
                "legal_rule.name_required", "Nome da regra obrigatório (até 120 caracteres)");
        Guard.require(c.minDaysOverdue() == null || (c.minDaysOverdue() >= 0 && c.minDaysOverdue() <= 36500),
                "legal_rule.days_invalid", "Atraso mínimo inválido");
        Guard.require(c.minOpenBalance() == null || !c.minOpenBalance().isNegative(), "legal_rule.balance_invalid",
                "Saldo mínimo inválido");
        Guard.require(c.minBrokenAgreements() == null || (c.minBrokenAgreements() >= 1 && c.minBrokenAgreements() <= 100),
                "legal_rule.broken_agreements_invalid", "Quantidade de acordos quebrados inválida");
        Guard.require(c.prescriptionWithinDays() == null
                        || (c.prescriptionWithinDays() >= 1 && c.prescriptionWithinDays() <= 3650),
                "legal_rule.prescription_days_invalid", "Janela de prescrição inválida");
        Guard.require(c.minDaysOverdue() != null || c.minOpenBalance() != null || c.requireCollateral()
                        || c.requireExecutiveTitle() || c.minBrokenAgreements() != null || c.prescriptionWithinDays() != null,
                "legal_rule.criteria_required", "Informe ao menos um critério");
        boolean changed = !c.name().trim().equals(name) || !java.util.Objects.equals(c.minDaysOverdue(), minDaysOverdue)
                || !java.util.Objects.equals(c.minOpenBalance(), minOpenBalance) || c.requireCollateral() != requireCollateral
                || c.requireExecutiveTitle() != requireExecutiveTitle
                || !java.util.Objects.equals(c.minBrokenAgreements(), minBrokenAgreements)
                || !java.util.Objects.equals(c.prescriptionWithinDays(), prescriptionWithinDays);
        this.name = c.name().trim();
        this.minDaysOverdue = c.minDaysOverdue();
        this.minOpenBalance = c.minOpenBalance();
        this.requireCollateral = c.requireCollateral();
        this.requireExecutiveTitle = c.requireExecutiveTitle();
        this.minBrokenAgreements = c.minBrokenAgreements();
        this.prescriptionWithinDays = c.prescriptionWithinDays();
        return changed;
    }

    public boolean setActive(boolean active) {
        boolean changed = this.active != active;
        this.active = active;
        return changed;
    }

    public boolean appliesTo(UUID creditor, UUID portfolio) {
        return active && creditorId.equals(creditor) && (portfolioId == null || portfolioId.equals(portfolio));
    }

    public Criteria criteria() {
        return new Criteria(name, minDaysOverdue, minOpenBalance, requireCollateral, requireExecutiveTitle,
                minBrokenAgreements, prescriptionWithinDays);
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public UUID getPortfolioId() {
        return portfolioId;
    }

    public String getName() {
        return name;
    }

    public boolean isActive() {
        return active;
    }

    public Integer getMinDaysOverdue() {
        return minDaysOverdue;
    }

    public Money getMinOpenBalance() {
        return minOpenBalance;
    }

    public boolean isRequireCollateral() {
        return requireCollateral;
    }

    public boolean isRequireExecutiveTitle() {
        return requireExecutiveTitle;
    }

    public Integer getMinBrokenAgreements() {
        return minBrokenAgreements;
    }

    public Integer getPrescriptionWithinDays() {
        return prescriptionWithinDays;
    }
}
