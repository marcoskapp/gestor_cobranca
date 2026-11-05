package br.com.gestorcobranca.collection.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * Parâmetros das contestações por assessoria (enquanto não gravados, valem os padrões de {@link Values#DEFAULTS}).
 * O prazo padrão de resposta ao devedor continua em {@link CollectionSettings} ({@code disputeResponseDays}).
 */
@Entity
@Table(name = "dispute_settings", schema = "collection")
public class DisputeSettings extends TenantScopedEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "distribution_mode", nullable = false, length = 20)
    private DisputeDistributionMode distributionMode;

    @Column(name = "internal_days", nullable = false)
    private int internalDays;

    @Column(name = "creditor_request_days", nullable = false)
    private int creditorRequestDays;

    @Column(name = "reminder_days_before", nullable = false)
    private int reminderDaysBefore;

    @Column(name = "evidence_link_days", nullable = false)
    private int evidenceLinkDays;

    protected DisputeSettings() {
    }

    public DisputeSettings(Values values) {
        define(values);
    }

    /**
     * @param distributionMode    MANUAL ou LEAST_LOADED
     * @param internalDays        prazo interno padrão (SLA da equipe), em dias corridos (1–90); limitado ao prazo de
     *                            resposta ao devedor
     * @param creditorRequestDays prazo padrão pedido ao credor, em dias corridos (1–60)
     * @param reminderDaysBefore  dias antes do prazo do credor para o lembrete automático (0 = sem lembrete; até 10)
     * @param evidenceLinkDays    validade do link seguro das evidências enviadas ao credor (1–30 dias)
     */
    public record Values(DisputeDistributionMode distributionMode, int internalDays, int creditorRequestDays,
                         int reminderDaysBefore, int evidenceLinkDays) {

        public static final Values DEFAULTS = new Values(DisputeDistributionMode.MANUAL, 7, 5, 1, 7);

        public Values {
            Guard.require(distributionMode != null, "dispute_settings.mode_required", "Modo de distribuição obrigatório");
            Guard.require(internalDays >= 1 && internalDays <= 90, "dispute_settings.internal_days_invalid",
                    "Prazo interno entre 1 e 90 dias");
            Guard.require(creditorRequestDays >= 1 && creditorRequestDays <= 60,
                    "dispute_settings.creditor_days_invalid", "Prazo pedido ao credor entre 1 e 60 dias");
            Guard.require(reminderDaysBefore >= 0 && reminderDaysBefore <= 10, "dispute_settings.reminder_invalid",
                    "Lembrete entre 0 e 10 dias antes do prazo");
            Guard.require(evidenceLinkDays >= 1 && evidenceLinkDays <= 30, "dispute_settings.link_days_invalid",
                    "Validade do link entre 1 e 30 dias");
        }
    }

    public void define(Values values) {
        Guard.require(values != null, "dispute_settings.required", "Parâmetros obrigatórios");
        this.distributionMode = values.distributionMode();
        this.internalDays = values.internalDays();
        this.creditorRequestDays = values.creditorRequestDays();
        this.reminderDaysBefore = values.reminderDaysBefore();
        this.evidenceLinkDays = values.evidenceLinkDays();
    }

    public Values values() {
        return new Values(distributionMode, internalDays, creditorRequestDays, reminderDaysBefore, evidenceLinkDays);
    }
}
