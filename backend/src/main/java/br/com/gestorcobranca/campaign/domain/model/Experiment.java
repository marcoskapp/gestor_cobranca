package br.com.gestorcobranca.campaign.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Percentage;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Teste A/B (F25) de uma campanha: variantes (JSON de {@link ExperimentVariant}), grupo de controle
 * ({@code controlShare}% dos devedores não recebem nenhuma ação da campanha), sal da atribuição
 * estável por devedor, horizonte de observação e amostra mínima por braço. Ao iniciar, variantes,
 * sal, controle e horizonte ficam congelados (gatilho no banco).
 */
@Entity
@Table(name = "experiment", schema = "campaign")
public class Experiment extends TenantScopedEntity {

    private static final BigDecimal MAX_CONTROL = new BigDecimal("50");

    @Column(name = "campaign_id", nullable = false, updatable = false)
    private UUID campaignId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "hypothesis", length = 500)
    private String hypothesis;

    @Enumerated(EnumType.STRING)
    @Column(name = "primary_metric", nullable = false, length = 30)
    private ExperimentMetric primaryMetric;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ExperimentStatus status;

    @Column(name = "control_share", nullable = false)
    private Percentage controlShare;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "variants", nullable = false)
    private String variants;

    @Column(name = "salt", nullable = false, length = 64, updatable = false)
    private String salt;

    @Column(name = "observation_days", nullable = false)
    private int observationDays;

    @Column(name = "min_sample_per_arm", nullable = false)
    private int minSamplePerArm;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "started_by")
    private UUID startedBy;

    @Column(name = "stopped_at")
    private Instant stoppedAt;

    @Column(name = "stopped_by")
    private UUID stoppedBy;

    @Column(name = "stop_reason", length = 300)
    private String stopReason;

    protected Experiment() {
    }

    public Experiment(UUID campaignId, String salt, Definition definition) {
        this.campaignId = campaignId;
        this.salt = salt;
        this.status = ExperimentStatus.DRAFT;
        apply(definition);
    }

    /** Parâmetros editáveis no rascunho. @param variantsJson lista de {@link ExperimentVariant} validada */
    public record Definition(String name, String hypothesis, ExperimentMetric primaryMetric, Percentage controlShare,
                             String variantsJson, int observationDays, int minSamplePerArm) {
    }

    public void update(Definition definition) {
        Guard.requireState(status == ExperimentStatus.DRAFT, "experiment.not_draft",
                "Experimento iniciado não pode ser alterado");
        apply(definition);
    }

    private void apply(Definition d) {
        Guard.require(d.name() != null && !d.name().isBlank() && d.name().length() <= 120, "experiment.name_invalid",
                "Nome obrigatório (até 120)");
        Guard.require(d.hypothesis() == null || d.hypothesis().length() <= 500, "experiment.hypothesis_invalid",
                "Hipótese com até 500 caracteres");
        Guard.require(d.controlShare() != null && d.controlShare().value().compareTo(MAX_CONTROL) <= 0,
                "experiment.control_share_invalid", "Grupo de controle entre 0% e 50%");
        Guard.require(d.observationDays() >= 1 && d.observationDays() <= 180, "experiment.observation_invalid",
                "Horizonte de observação entre 1 e 180 dias");
        Guard.require(d.minSamplePerArm() >= 10 && d.minSamplePerArm() <= 1_000_000, "experiment.min_sample_invalid",
                "Amostra mínima por braço entre 10 e 1.000.000");
        this.name = d.name().trim();
        this.hypothesis = d.hypothesis();
        this.primaryMetric = d.primaryMetric() == null ? ExperimentMetric.PAYMENT : d.primaryMetric();
        this.controlShare = d.controlShare();
        this.variants = d.variantsJson();
        this.observationDays = d.observationDays();
        this.minSamplePerArm = d.minSamplePerArm();
    }

    public void start(Instant now, UUID actorId) {
        Guard.requireState(status == ExperimentStatus.DRAFT, "experiment.not_draft", "Experimento já iniciado");
        this.status = ExperimentStatus.RUNNING;
        this.startedAt = now;
        this.startedBy = actorId;
    }

    public void stop(String reason, Instant now, UUID actorId) {
        Guard.requireState(status == ExperimentStatus.RUNNING, "experiment.not_running", "Experimento não está em execução");
        Guard.require(reason != null && !reason.isBlank() && reason.length() <= 300, "experiment.stop_reason_required",
                "Informe o motivo do encerramento (até 300)");
        this.status = ExperimentStatus.STOPPED;
        this.stoppedAt = now;
        this.stoppedBy = actorId;
        this.stopReason = reason.trim();
    }

    public boolean isRunning() {
        return status == ExperimentStatus.RUNNING;
    }

    public UUID getCampaignId() {
        return campaignId;
    }

    public String getName() {
        return name;
    }

    public String getHypothesis() {
        return hypothesis;
    }

    public ExperimentMetric getPrimaryMetric() {
        return primaryMetric;
    }

    public ExperimentStatus getStatus() {
        return status;
    }

    public Percentage getControlShare() {
        return controlShare;
    }

    public String getVariants() {
        return variants;
    }

    public String getSalt() {
        return salt;
    }

    public int getObservationDays() {
        return observationDays;
    }

    public int getMinSamplePerArm() {
        return minSamplePerArm;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public UUID getStartedBy() {
        return startedBy;
    }

    public Instant getStoppedAt() {
        return stoppedAt;
    }

    public UUID getStoppedBy() {
        return stoppedBy;
    }

    public String getStopReason() {
        return stopReason;
    }
}
