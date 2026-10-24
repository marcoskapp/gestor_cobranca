package br.com.gestorcobranca.scoring.domain.model;

import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Relatório de monitoramento do campeão numa data de snapshot: estabilidade da população (PSI do
 * score e de cada feature contra a referência do modelo), desempenho realizado nas safras com
 * desfecho apurado (campeão e desafiante) e alertas de deriva.
 */
@Entity
@Table(name = "monitoring_report", schema = "scoring")
public class MonitoringReport extends TenantScopedEntity {

    public enum Status { STABLE, WARNING, ALERT }

    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Column(name = "model_id", nullable = false, updatable = false)
    private UUID modelId;

    @Column(name = "reference_date", nullable = false, updatable = false)
    private LocalDate referenceDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, updatable = false, length = 20)
    private Status status;

    @Column(name = "population", nullable = false, updatable = false)
    private int population;

    @Column(name = "score_psi", updatable = false, precision = 9, scale = 6)
    private BigDecimal scorePsi;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "feature_psi", nullable = false, updatable = false)
    private String featurePsi;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "realized", nullable = false, updatable = false)
    private String realized;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "alerts", nullable = false, updatable = false)
    private String alerts;

    protected MonitoringReport() {
    }

    public MonitoringReport(UUID runId, UUID modelId, LocalDate referenceDate, Status status, int population,
                            BigDecimal scorePsi, String featurePsi, String realized, String alerts) {
        this.runId = runId;
        this.modelId = modelId;
        this.referenceDate = referenceDate;
        this.status = status;
        this.population = population;
        this.scorePsi = scorePsi;
        this.featurePsi = featurePsi;
        this.realized = realized;
        this.alerts = alerts;
    }

    public UUID getRunId() {
        return runId;
    }

    public UUID getModelId() {
        return modelId;
    }

    public LocalDate getReferenceDate() {
        return referenceDate;
    }

    public Status getStatus() {
        return status;
    }

    public int getPopulation() {
        return population;
    }

    public BigDecimal getScorePsi() {
        return scorePsi;
    }

    public String getFeaturePsi() {
        return featurePsi;
    }

    public String getRealized() {
        return realized;
    }

    public String getAlerts() {
        return alerts;
    }
}
