package br.com.gestorcobranca.compliance.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * Política de retenção de uma categoria de dado na assessoria. O prazo fica na coluna da unidade da categoria
 * ({@code retention_months} ou {@code retention_days}; a outra fica nula — {@code check} no banco).
 * <p>
 * Categoria sem padrão do sistema nasce desabilitada: o descarte só ocorre depois que alguém a habilita
 * conscientemente (preferencialmente após uma simulação). Categoria com padrão do sistema descarta sempre: a política
 * só ajusta o prazo e não pode ser desabilitada.
 */
@Entity
@Table(name = "retention_policy", schema = "compliance")
public class RetentionPolicy extends TenantScopedEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 30, updatable = false)
    private RetentionCategory category;

    @Column(name = "retention_months")
    private Integer retentionMonths;

    @Column(name = "retention_days")
    private Integer retentionDays;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 20, updatable = false)
    private RetentionAction action;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    protected RetentionPolicy() {
    }

    public RetentionPolicy(RetentionCategory category, int retentionAmount) {
        Guard.require(category != null, "retention.category_required", "Categoria obrigatória");
        this.category = category;
        this.action = category.action();
        this.enabled = category.hasSystemDefault();
        changeRetention(retentionAmount);
    }

    /** Novo prazo, na unidade da categoria e dentro dos limites dela. */
    public void changeRetention(int amount) {
        Guard.require(category.accepts(amount), "retention.amount_invalid",
                "Prazo de retenção deve estar entre %d e %d %s".formatted(category.minAmount(), category.maxAmount(),
                        category.unit() == RetentionUnit.DAYS ? "dias" : "meses"));
        if (category.unit() == RetentionUnit.DAYS) {
            this.retentionDays = amount;
            this.retentionMonths = null;
        } else {
            this.retentionMonths = amount;
            this.retentionDays = null;
        }
    }

    public void enable() {
        this.enabled = true;
    }

    public void disable() {
        Guard.require(!category.hasSystemDefault(), "retention.discard_mandatory",
                "O descarte desta categoria é obrigatório: ajuste o prazo em vez de desabilitar");
        this.enabled = false;
    }

    public RetentionCategory getCategory() {
        return category;
    }

    /** Prazo na unidade da categoria ({@link #getRetentionUnit()}). */
    public int getRetentionAmount() {
        return category.unit() == RetentionUnit.DAYS ? retentionDays : retentionMonths;
    }

    public RetentionUnit getRetentionUnit() {
        return category.unit();
    }

    public RetentionAction getAction() {
        return action;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
