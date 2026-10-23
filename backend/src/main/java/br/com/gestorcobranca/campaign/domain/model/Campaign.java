package br.com.gestorcobranca.campaign.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Campanha de um credor (carteira opcional) sobre um segmento, com régua versionada
 * ({@link Cadence}) e parâmetros de execução.
 * <p>
 * <b>Concorrência entre campanhas:</b> inscrições em andamento de campanhas com o mesmo
 * {@code exclusivityGroup} são mutuamente exclusivas por devedor; a de maior {@code priority}
 * substitui a menor (empate mantém a que já está em andamento). Grupo nulo = campanha
 * compatível com qualquer outra (ex.: lembrete de parcela de acordo).
 */
@Entity
@Table(name = "campaign", schema = "campaign")
public class Campaign extends TenantScopedEntity {

    public static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,59}");
    private static final Pattern GROUP = Pattern.compile("[A-Z][A-Z0-9_]{0,59}");

    @Column(name = "code", nullable = false, length = 60, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "portfolio_id", updatable = false)
    private UUID portfolioId;

    @Column(name = "segment_id", nullable = false)
    private UUID segmentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "purpose", nullable = false, length = 30)
    private CampaignPurpose purpose;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CampaignStatus status;

    @Column(name = "priority", nullable = false)
    private int priority;

    @Column(name = "exclusivity_group", length = 60)
    private String exclusivityGroup;

    @Column(name = "reenroll_after_days")
    private Integer reenrollAfterDays;

    @Column(name = "attribution_window_days", nullable = false)
    private int attributionWindowDays;

    @Column(name = "payment_pause_days")
    private Integer paymentPauseDays;

    @Column(name = "max_deferral_hours", nullable = false)
    private int maxDeferralHours;

    @Column(name = "exit_when_out_of_segment", nullable = false)
    private boolean exitWhenOutOfSegment;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "activated_by")
    private UUID activatedBy;

    @Column(name = "paused_at")
    private Instant pausedAt;

    @Column(name = "pause_note", length = 300)
    private String pauseNote;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected Campaign() {
    }

    public Campaign(String code, UUID creditorId, UUID portfolioId, Settings settings) {
        Guard.require(code != null && CODE.matcher(code).matches(), "campaign.code_invalid",
                "Código: letras maiúsculas, dígitos e _ (2 a 60)");
        Guard.require(creditorId != null, "campaign.creditor_required", "Informe o credor");
        this.code = code;
        this.creditorId = creditorId;
        this.portfolioId = portfolioId;
        this.status = CampaignStatus.DRAFT;
        apply(settings);
    }

    /**
     * Parâmetros editáveis.
     *
     * @param reenrollAfterDays    nulo = o caso nunca volta a ser inscrito depois de encerrar
     * @param attributionWindowDays dias após o fim da inscrição em que resultados ainda são atribuídos
     * @param paymentPauseDays     retomada automática após pagamento confirmado (nulo = só manual)
     * @param maxDeferralHours     adiamento máximo de uma etapa negada por horário/limite do porteiro
     */
    public record Settings(String name, String description, UUID segmentId, CampaignPurpose purpose, int priority,
                           String exclusivityGroup, Integer reenrollAfterDays, int attributionWindowDays,
                           Integer paymentPauseDays, int maxDeferralHours, boolean exitWhenOutOfSegment) {
    }

    public void update(Settings settings) {
        Guard.requireState(status != CampaignStatus.FINISHED, "campaign.finished", "Campanha encerrada");
        Guard.requireState(status != CampaignStatus.ACTIVE || settings.segmentId().equals(segmentId),
                "campaign.active_segment_locked", "Pause a campanha para trocar o segmento");
        apply(settings);
    }

    private void apply(Settings s) {
        Guard.require(s.name() != null && !s.name().isBlank() && s.name().length() <= 120, "campaign.name_invalid",
                "Nome obrigatório (até 120)");
        Guard.require(s.description() == null || s.description().length() <= 500, "campaign.description_invalid",
                "Descrição com até 500 caracteres");
        Guard.require(s.segmentId() != null, "campaign.segment_required", "Informe o segmento");
        Guard.require(s.priority() >= 0 && s.priority() <= 1000, "campaign.priority_invalid", "Prioridade entre 0 e 1000");
        Guard.require(s.exclusivityGroup() == null || GROUP.matcher(s.exclusivityGroup()).matches(),
                "campaign.exclusivity_group_invalid", "Grupo de exclusividade: letras maiúsculas, dígitos e _");
        Guard.require(s.reenrollAfterDays() == null || (s.reenrollAfterDays() >= 1 && s.reenrollAfterDays() <= 3650),
                "campaign.reenroll_invalid", "Reinscrição entre 1 e 3650 dias");
        Guard.require(s.attributionWindowDays() >= 0 && s.attributionWindowDays() <= 90, "campaign.attribution_window_invalid",
                "Janela de atribuição entre 0 e 90 dias");
        Guard.require(s.paymentPauseDays() == null || (s.paymentPauseDays() >= 1 && s.paymentPauseDays() <= 180),
                "campaign.payment_pause_invalid", "Pausa por pagamento entre 1 e 180 dias");
        Guard.require(s.maxDeferralHours() >= 0 && s.maxDeferralHours() <= 168, "campaign.max_deferral_invalid",
                "Adiamento máximo entre 0 e 168 horas");
        this.name = s.name().trim();
        this.description = s.description();
        this.segmentId = s.segmentId();
        this.purpose = s.purpose() == null ? CampaignPurpose.EXTRAJUDICIAL_COLLECTION : s.purpose();
        this.priority = s.priority();
        this.exclusivityGroup = s.exclusivityGroup();
        this.reenrollAfterDays = s.reenrollAfterDays();
        this.attributionWindowDays = s.attributionWindowDays();
        this.paymentPauseDays = s.paymentPauseDays();
        this.maxDeferralHours = s.maxDeferralHours();
        this.exitWhenOutOfSegment = s.exitWhenOutOfSegment();
    }

    public void activate(Instant now, UUID actorId) {
        Guard.requireState(status == CampaignStatus.DRAFT || status == CampaignStatus.PAUSED, "campaign.not_activatable",
                "Somente campanhas em rascunho ou pausadas podem ser ativadas");
        this.status = CampaignStatus.ACTIVE;
        this.activatedAt = now;
        this.activatedBy = actorId;
        this.pausedAt = null;
        this.pauseNote = null;
    }

    public void pause(String note, Instant now) {
        Guard.requireState(status == CampaignStatus.ACTIVE, "campaign.not_active", "A campanha não está ativa");
        Guard.require(note != null && !note.isBlank() && note.length() <= 300, "campaign.pause_note_required",
                "Informe o motivo da pausa (até 300)");
        this.status = CampaignStatus.PAUSED;
        this.pausedAt = now;
        this.pauseNote = note.trim();
    }

    public void finish(Instant now) {
        Guard.requireState(status != CampaignStatus.FINISHED, "campaign.finished", "Campanha já encerrada");
        this.status = CampaignStatus.FINISHED;
        this.finishedAt = now;
    }

    public boolean isActive() {
        return status == CampaignStatus.ACTIVE;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public UUID getPortfolioId() {
        return portfolioId;
    }

    public UUID getSegmentId() {
        return segmentId;
    }

    public CampaignPurpose getPurpose() {
        return purpose;
    }

    public CampaignStatus getStatus() {
        return status;
    }

    public int getPriority() {
        return priority;
    }

    public String getExclusivityGroup() {
        return exclusivityGroup;
    }

    public Integer getReenrollAfterDays() {
        return reenrollAfterDays;
    }

    public int getAttributionWindowDays() {
        return attributionWindowDays;
    }

    public Integer getPaymentPauseDays() {
        return paymentPauseDays;
    }

    public int getMaxDeferralHours() {
        return maxDeferralHours;
    }

    public boolean isExitWhenOutOfSegment() {
        return exitWhenOutOfSegment;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }

    public UUID getActivatedBy() {
        return activatedBy;
    }

    public Instant getPausedAt() {
        return pausedAt;
    }

    public String getPauseNote() {
        return pauseNote;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }
}
