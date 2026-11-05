package br.com.gestorcobranca.collection.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Prazo de resposta ao devedor e prazo interno (SLA) para um tipo de contestação, um credor ou um tipo num credor.
 * Hierarquia na abertura (do mais específico ao mais geral): credor + tipo → credor → tipo → padrão da assessoria.
 * Os prazos são fixados na contestação quando ela é aberta.
 */
@Entity
@Table(name = "dispute_deadline_rule", schema = "collection")
public class DisputeDeadlineRule extends TenantScopedEntity {

    @Column(name = "creditor_id", updatable = false)
    private UUID creditorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", length = 30, updatable = false)
    private DisputeType type;

    @Column(name = "response_days", nullable = false)
    private int responseDays;

    @Column(name = "internal_days", nullable = false)
    private int internalDays;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected DisputeDeadlineRule() {
    }

    public DisputeDeadlineRule(UUID creditorId, DisputeType type, int responseDays, int internalDays) {
        Guard.require(creditorId != null || type != null, "dispute_rule.scope_required",
                "Informe o tipo, o credor ou ambos (o padrão da assessoria fica nos parâmetros)");
        this.creditorId = creditorId;
        this.type = type;
        this.active = true;
        define(responseDays, internalDays);
    }

    public void define(int responseDays, int internalDays) {
        Guard.requireState(active, "dispute_rule.inactive", "Regra removida");
        Guard.require(responseDays >= 1 && responseDays <= 90, "dispute_rule.response_days_invalid",
                "Prazo de resposta entre 1 e 90 dias");
        Guard.require(internalDays >= 1 && internalDays <= responseDays, "dispute_rule.internal_days_invalid",
                "Prazo interno entre 1 dia e o prazo de resposta");
        this.responseDays = responseDays;
        this.internalDays = internalDays;
    }

    public void deactivate() {
        Guard.requireState(active, "dispute_rule.inactive", "Regra já removida");
        this.active = false;
    }

    public DeadlineSource source() {
        if (creditorId != null && type != null) {
            return DeadlineSource.CREDITOR_TYPE;
        }
        return creditorId != null ? DeadlineSource.CREDITOR : DeadlineSource.TYPE;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public DisputeType getType() {
        return type;
    }

    public int getResponseDays() {
        return responseDays;
    }

    public int getInternalDays() {
        return internalDays;
    }

    public boolean isActive() {
        return active;
    }
}
