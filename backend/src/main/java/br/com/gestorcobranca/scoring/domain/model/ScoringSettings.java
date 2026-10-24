package br.com.gestorcobranca.scoring.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Parâmetros do score da assessoria (um registro por tenant) e o registro da base legal. Nenhum
 * score é calculado enquanto a base legal não for registrada por quem tem {@code SCORING_MANAGE}.
 */
@Entity
@Table(name = "settings", schema = "scoring")
public class ScoringSettings extends TenantScopedEntity {

    @Column(name = "outcome_days", nullable = false)
    private int outcomeDays;

    @Column(name = "min_cases", nullable = false)
    private int minCases;

    @Column(name = "min_positives", nullable = false)
    private int minPositives;

    @Column(name = "min_partition_positives", nullable = false)
    private int minPartitionPositives;

    @Column(name = "validation_share", nullable = false, precision = 5, scale = 4)
    private BigDecimal validationShare;

    @Column(name = "l2_lambda", nullable = false, precision = 12, scale = 6)
    private BigDecimal l2Lambda;

    @Column(name = "min_auc", nullable = false, precision = 5, scale = 4)
    private BigDecimal minAuc;

    @Column(name = "training_window_days", nullable = false)
    private int trainingWindowDays;

    @Column(name = "snapshot_interval_days", nullable = false)
    private int snapshotIntervalDays;

    @Column(name = "publish_min_delta", nullable = false)
    private int publishMinDelta;

    @Column(name = "psi_warning", nullable = false, precision = 6, scale = 4)
    private BigDecimal psiWarning;

    @Column(name = "psi_alert", nullable = false, precision = 6, scale = 4)
    private BigDecimal psiAlert;

    @Column(name = "band_a", nullable = false)
    private int bandA;

    @Column(name = "band_b", nullable = false)
    private int bandB;

    @Column(name = "band_c", nullable = false)
    private int bandC;

    @Column(name = "band_d", nullable = false)
    private int bandD;

    @Column(name = "default_paid_share", nullable = false, precision = 5, scale = 4)
    private BigDecimal defaultPaidShare;

    @Column(name = "auto_train", nullable = false)
    private boolean autoTrain;

    @Enumerated(EnumType.STRING)
    @Column(name = "legal_basis", length = 30)
    private LegalBasis legalBasis;

    @Column(name = "legal_basis_reference", length = 500)
    private String legalBasisReference;

    @Column(name = "legal_basis_registered_at")
    private Instant legalBasisRegisteredAt;

    @Column(name = "legal_basis_registered_by")
    private UUID legalBasisRegisteredBy;

    protected ScoringSettings() {
    }

    /** Parâmetros padrão (de {@code app.scoring.defaults}); base legal ainda não registrada. */
    public ScoringSettings(Parameters defaults) {
        apply(defaults);
    }

    /**
     * @param outcomeDays          N da janela "pagamento confirmado em N dias"
     * @param snapshotIntervalDays a cada quantos dias a escoragem diária grava o snapshot de features
     * @param publishMinDelta      variação mínima do score (pontos 0–1000) que publica {@code CaseScoreChanged}
     *                             sem mudança de faixa
     */
    public record Parameters(int outcomeDays, int minCases, int minPositives, int minPartitionPositives,
                             BigDecimal validationShare, BigDecimal l2Lambda, BigDecimal minAuc, int trainingWindowDays,
                             int snapshotIntervalDays, int publishMinDelta, BigDecimal psiWarning, BigDecimal psiAlert,
                             BandCutoffs bands, BigDecimal defaultPaidShare, boolean autoTrain) {

        public Parameters {
            Guard.require(outcomeDays >= 7 && outcomeDays <= 180, "scoring.settings.outcome_days_invalid",
                    "Janela de desfecho entre 7 e 180 dias");
            Guard.require(minCases >= 100 && minPositives >= 20 && minPositives <= minCases,
                    "scoring.settings.min_data_invalid", "Critério mínimo: ao menos 100 casos e 20 positivos");
            Guard.require(minPartitionPositives >= 5, "scoring.settings.partition_invalid",
                    "Mínimo de positivos por partição: ao menos 5");
            Guard.require(between(validationShare, "0.10", "0.50"), "scoring.settings.validation_share_invalid",
                    "Fração de validação entre 0,10 e 0,50");
            Guard.require(between(l2Lambda, "0.000001", "1000"), "scoring.settings.lambda_invalid",
                    "Regularização entre 0,000001 e 1000");
            Guard.require(between(minAuc, "0.50", "0.95"), "scoring.settings.min_auc_invalid", "AUC mínima entre 0,50 e 0,95");
            Guard.require(trainingWindowDays >= outcomeDays * 2 && trainingWindowDays <= 1095,
                    "scoring.settings.training_window_invalid", "Janela de treino entre 2× a de desfecho e 1095 dias");
            Guard.require(snapshotIntervalDays >= 1 && snapshotIntervalDays <= 31, "scoring.settings.snapshot_interval_invalid",
                    "Intervalo de snapshot entre 1 e 31 dias");
            Guard.require(publishMinDelta >= 1 && publishMinDelta <= 200, "scoring.settings.publish_delta_invalid",
                    "Variação mínima para publicar entre 1 e 200 pontos");
            Guard.require(between(psiWarning, "0.01", "1") && between(psiAlert, "0.01", "2")
                    && psiWarning.compareTo(psiAlert) < 0, "scoring.settings.psi_invalid",
                    "Limiares de PSI: atenção < alerta");
            Guard.require(bands != null, "scoring.band_cutoffs_invalid", "Cortes das faixas obrigatórios");
            Guard.require(between(defaultPaidShare, "0.01", "1"), "scoring.settings.paid_share_invalid",
                    "Fração paga típica entre 0,01 e 1");
        }

        private static boolean between(BigDecimal v, String min, String max) {
            return v != null && v.compareTo(new BigDecimal(min)) >= 0 && v.compareTo(new BigDecimal(max)) <= 0;
        }
    }

    public void apply(Parameters p) {
        this.outcomeDays = p.outcomeDays();
        this.minCases = p.minCases();
        this.minPositives = p.minPositives();
        this.minPartitionPositives = p.minPartitionPositives();
        this.validationShare = p.validationShare().setScale(4, RoundingMode.HALF_EVEN);
        this.l2Lambda = p.l2Lambda().setScale(6, RoundingMode.HALF_EVEN);
        this.minAuc = p.minAuc().setScale(4, RoundingMode.HALF_EVEN);
        this.trainingWindowDays = p.trainingWindowDays();
        this.snapshotIntervalDays = p.snapshotIntervalDays();
        this.publishMinDelta = p.publishMinDelta();
        this.psiWarning = p.psiWarning().setScale(4, RoundingMode.HALF_EVEN);
        this.psiAlert = p.psiAlert().setScale(4, RoundingMode.HALF_EVEN);
        this.bandA = p.bands().a();
        this.bandB = p.bands().b();
        this.bandC = p.bands().c();
        this.bandD = p.bands().d();
        this.defaultPaidShare = p.defaultPaidShare().setScale(4, RoundingMode.HALF_EVEN);
        this.autoTrain = p.autoTrain();
    }

    public Parameters parameters() {
        return new Parameters(outcomeDays, minCases, minPositives, minPartitionPositives, validationShare, l2Lambda,
                minAuc, trainingWindowDays, snapshotIntervalDays, publishMinDelta, psiWarning, psiAlert, bands(),
                defaultPaidShare, autoTrain);
    }

    /**
     * Registra (ou substitui) a base legal. Legítimo interesse exige a referência da avaliação de
     * legítimo interesse (LIA) — documento interno, não dado pessoal.
     */
    public void registerLegalBasis(LegalBasis basis, String reference, UUID actorId, Instant now) {
        Guard.require(basis != null, "scoring.legal_basis_required", "Base legal obrigatória");
        String ref = reference == null ? null : reference.trim();
        Guard.require(ref != null && ref.length() >= 5 && ref.length() <= 500, "scoring.legal_basis_reference_required",
                "Informe a referência do registro (ex.: avaliação de legítimo interesse) com 5 a 500 caracteres");
        this.legalBasis = basis;
        this.legalBasisReference = ref;
        this.legalBasisRegisteredAt = now;
        this.legalBasisRegisteredBy = actorId;
    }

    public boolean legalBasisRegistered() {
        return legalBasis != null && legalBasisRegisteredAt != null;
    }

    /** Dia de snapshot: épocas múltiplas do intervalo (determinístico entre instâncias e reprocessamentos). */
    public boolean isSnapshotDay(LocalDate date) {
        return Math.floorMod(date.toEpochDay(), snapshotIntervalDays) == 0;
    }

    public BandCutoffs bands() {
        return new BandCutoffs(bandA, bandB, bandC, bandD);
    }

    public int getOutcomeDays() {
        return outcomeDays;
    }

    public int getTrainingWindowDays() {
        return trainingWindowDays;
    }

    public int getPublishMinDelta() {
        return publishMinDelta;
    }

    public BigDecimal getPsiWarning() {
        return psiWarning;
    }

    public BigDecimal getPsiAlert() {
        return psiAlert;
    }

    public BigDecimal getMinAuc() {
        return minAuc;
    }

    public BigDecimal getL2Lambda() {
        return l2Lambda;
    }

    public BigDecimal getDefaultPaidShare() {
        return defaultPaidShare;
    }

    public boolean isAutoTrain() {
        return autoTrain;
    }

    public LegalBasis getLegalBasis() {
        return legalBasis;
    }

    public String getLegalBasisReference() {
        return legalBasisReference;
    }

    public Instant getLegalBasisRegisteredAt() {
        return legalBasisRegisteredAt;
    }

    public UUID getLegalBasisRegisteredBy() {
        return legalBasisRegisteredBy;
    }
}
