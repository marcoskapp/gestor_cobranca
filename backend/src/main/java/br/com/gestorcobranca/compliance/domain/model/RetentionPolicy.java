package br.com.gestorcobranca.compliance.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * Política de retenção de uma categoria de dado na assessoria. Nasce desabilitada: o descarte
 * só ocorre depois que alguém a habilita conscientemente (preferencialmente após uma simulação).
 */
@Entity
@Table(name = "retention_policy", schema = "compliance")
public class RetentionPolicy extends TenantScopedEntity {

    private static final int MAX_MONTHS = 1_200;

    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 30, updatable = false)
    private RetentionCategory category;

    @Column(name = "retention_months", nullable = false)
    private int retentionMonths;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 20, updatable = false)
    private RetentionAction action;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    protected RetentionPolicy() {
    }

    public RetentionPolicy(RetentionCategory category, int retentionMonths) {
        Guard.require(category != null, "retention.category_required", "Categoria obrigatória");
        this.category = category;
        this.action = category.action();
        this.enabled = false;
        changeRetention(retentionMonths);
    }

    public void changeRetention(int months) {
        Guard.require(months > 0 && months <= MAX_MONTHS, "retention.months_invalid",
                "Prazo de retenção deve estar entre 1 e %d meses".formatted(MAX_MONTHS));
        this.retentionMonths = months;
    }

    public void enable() {
        this.enabled = true;
    }

    public void disable() {
        this.enabled = false;
    }

    public RetentionCategory getCategory() {
        return category;
    }

    public int getRetentionMonths() {
        return retentionMonths;
    }

    public RetentionAction getAction() {
        return action;
    }

    public boolean isEnabled() {
        return enabled;
    }
}
