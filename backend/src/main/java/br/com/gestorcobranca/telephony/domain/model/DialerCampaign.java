package br.com.gestorcobranca.telephony.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Percentage;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalTime;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Campanha de discagem (F20): lista montada a partir de uma fila do collection (filtros opcionais
 * de credor e carteira), ritmo progressivo ou preditivo com taxa de abandono máxima
 * (padrão de mercado: 3% das chamadas atendidas por pessoa), tentativas com intervalo e janela de
 * horário própria — que se soma às janelas, feriados e limites do porteiro de contato do compliance.
 * <p>
 * Ciclo: {@code DRAFT → ACTIVE ⇄ PAUSED → FINISHED}. Parâmetros só mudam fora de {@code ACTIVE}.
 */
@Entity
@Table(name = "dialer_campaign", schema = "telephony")
public class DialerCampaign extends TenantScopedEntity {

    public static final BigDecimal MAX_RATIO_LIMIT = new BigDecimal("5.00");
    public static final Percentage DEFAULT_MAX_ABANDON = Percentage.of("3");
    private static final Percentage ABANDON_CEILING = Percentage.of("10");
    private static final Pattern CODE = Pattern.compile("[A-Z0-9_\\-]{2,40}");

    @Column(name = "code", nullable = false, length = 40, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "creditor_id", updatable = false)
    private UUID creditorId;

    @Column(name = "portfolio_id")
    private UUID portfolioId;

    @Column(name = "queue_id", nullable = false)
    private UUID queueId;

    @Column(name = "include_assigned", nullable = false)
    private boolean includeAssigned;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 20)
    private DialerMode mode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DialerCampaignStatus status;

    @Column(name = "max_dial_ratio", nullable = false, precision = 5, scale = 2)
    private BigDecimal maxDialRatio;

    @Column(name = "max_abandon_rate", nullable = false)
    private Percentage maxAbandonRate;

    @Column(name = "max_attempts", nullable = false)
    private int maxAttempts;

    @Column(name = "retry_interval_minutes", nullable = false)
    private int retryIntervalMinutes;

    @Column(name = "ring_timeout_seconds", nullable = false)
    private int ringTimeoutSeconds;

    @Column(name = "window_start")
    private LocalTime windowStart;

    @Column(name = "window_end")
    private LocalTime windowEnd;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "paused_at")
    private Instant pausedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "last_loaded_at")
    private Instant lastLoadedAt;

    @Column(name = "entries_loaded", nullable = false)
    private int entriesLoaded;

    protected DialerCampaign() {
    }

    public DialerCampaign(String code, UUID creditorId, UUID queueId, Settings settings) {
        Guard.require(code != null && CODE.matcher(code.trim().toUpperCase()).matches(), "dialer_campaign.code_invalid",
                "Código inválido (2 a 40 caracteres: letras, dígitos, _ e -)");
        this.code = code.trim().toUpperCase();
        this.creditorId = creditorId;
        this.status = DialerCampaignStatus.DRAFT;
        Guard.require(queueId != null, "dialer_campaign.queue_required", "Fila de origem obrigatória");
        this.queueId = queueId;
        configure(settings);
    }

    /** Altera parâmetros (fora de {@code ACTIVE}; nunca depois de encerrada). */
    public void configure(Settings s) {
        Guard.requireState(status != DialerCampaignStatus.ACTIVE, "dialer_campaign.active",
                "Pause a campanha para alterar os parâmetros");
        Guard.requireState(status != DialerCampaignStatus.FINISHED, "dialer_campaign.finished", "Campanha encerrada");
        Guard.require(s != null, "dialer_campaign.settings_required", "Parâmetros obrigatórios");
        Guard.require(s.name() != null && !s.name().isBlank() && s.name().length() <= 120, "dialer_campaign.name_invalid",
                "Nome obrigatório (até 120 caracteres)");
        Guard.require(s.mode() != null, "dialer_campaign.mode_required", "Modo obrigatório");
        BigDecimal ratio = s.maxDialRatio() == null ? BigDecimal.ONE : s.maxDialRatio().setScale(2, RoundingMode.HALF_EVEN);
        Guard.require(ratio.compareTo(BigDecimal.ONE) >= 0 && ratio.compareTo(MAX_RATIO_LIMIT) <= 0,
                "dialer_campaign.ratio_invalid", "Razão máxima de discagem entre 1 e 5 chamadas por operador");
        Percentage abandon = s.maxAbandonRate() == null ? DEFAULT_MAX_ABANDON : s.maxAbandonRate();
        Guard.require(abandon.value().signum() > 0 && !abandon.exceeds(ABANDON_CEILING),
                "dialer_campaign.abandon_invalid", "Abandono máximo entre 0 e 10% (padrão de mercado: 3%)");
        int attempts = s.maxAttempts() == null ? 3 : s.maxAttempts();
        Guard.require(attempts >= 1 && attempts <= 20, "dialer_campaign.attempts_invalid", "Tentativas entre 1 e 20");
        int retry = s.retryIntervalMinutes() == null ? 120 : s.retryIntervalMinutes();
        Guard.require(retry >= 5 && retry <= 10_080, "dialer_campaign.retry_invalid",
                "Intervalo entre tentativas de 5 minutos a 7 dias");
        int ring = s.ringTimeoutSeconds() == null ? 25 : s.ringTimeoutSeconds();
        Guard.require(ring >= 10 && ring <= 60, "dialer_campaign.ring_timeout_invalid",
                "Tempo de toque entre 10 e 60 segundos");
        Guard.require((s.windowStart() == null) == (s.windowEnd() == null), "dialer_campaign.window_invalid",
                "Informe início e fim da janela, ou nenhum");
        Guard.require(s.windowStart() == null || s.windowStart().isBefore(s.windowEnd()), "dialer_campaign.window_invalid",
                "Início da janela deve ser anterior ao fim");
        this.name = s.name().trim();
        this.portfolioId = s.portfolioId();
        this.includeAssigned = s.includeAssigned();
        this.mode = s.mode();
        this.maxDialRatio = s.mode() == DialerMode.PROGRESSIVE ? BigDecimal.ONE.setScale(2) : ratio;
        this.maxAbandonRate = abandon;
        this.maxAttempts = attempts;
        this.retryIntervalMinutes = retry;
        this.ringTimeoutSeconds = ring;
        this.windowStart = s.windowStart();
        this.windowEnd = s.windowEnd();
    }

    public void activate(Instant now) {
        Guard.requireState(status == DialerCampaignStatus.DRAFT || status == DialerCampaignStatus.PAUSED,
                "dialer_campaign.not_activatable", "Somente campanhas em rascunho ou pausadas são ativadas");
        this.status = DialerCampaignStatus.ACTIVE;
        this.activatedAt = now;
        this.pausedAt = null;
    }

    public void pause(Instant now) {
        Guard.requireState(status == DialerCampaignStatus.ACTIVE, "dialer_campaign.not_active", "Campanha não está ativa");
        this.status = DialerCampaignStatus.PAUSED;
        this.pausedAt = now;
    }

    public void finish(Instant now) {
        Guard.requireState(status != DialerCampaignStatus.FINISHED, "dialer_campaign.finished", "Campanha já encerrada");
        this.status = DialerCampaignStatus.FINISHED;
        this.finishedAt = now;
    }

    public void loaded(int added, Instant now) {
        this.entriesLoaded += Math.max(0, added);
        this.lastLoadedAt = now;
    }

    public boolean isActive() {
        return status == DialerCampaignStatus.ACTIVE;
    }

    /** Dentro da janela própria da campanha (hora local de operação); sem janela = sempre. */
    public boolean withinWindow(LocalTime localTime) {
        return windowStart == null || (!localTime.isBefore(windowStart) && localTime.isBefore(windowEnd));
    }

    /**
     * @param maxDialRatio  chamadas simultâneas por operador livre no preditivo (1 a 5; progressivo = 1)
     * @param maxAbandonRate abandono máximo (% das atendidas por pessoa); nulo = 3%
     * @param windowStart   janela própria (opcional, fuso de operação)
     */
    public record Settings(String name, UUID portfolioId, boolean includeAssigned, DialerMode mode,
                           BigDecimal maxDialRatio, Percentage maxAbandonRate, Integer maxAttempts,
                           Integer retryIntervalMinutes, Integer ringTimeoutSeconds, LocalTime windowStart,
                           LocalTime windowEnd) {
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public UUID getPortfolioId() {
        return portfolioId;
    }

    public UUID getQueueId() {
        return queueId;
    }

    public boolean isIncludeAssigned() {
        return includeAssigned;
    }

    public DialerMode getMode() {
        return mode;
    }

    public DialerCampaignStatus getStatus() {
        return status;
    }

    public BigDecimal getMaxDialRatio() {
        return maxDialRatio;
    }

    public Percentage getMaxAbandonRate() {
        return maxAbandonRate;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public int getRetryIntervalMinutes() {
        return retryIntervalMinutes;
    }

    public int getRingTimeoutSeconds() {
        return ringTimeoutSeconds;
    }

    public LocalTime getWindowStart() {
        return windowStart;
    }

    public LocalTime getWindowEnd() {
        return windowEnd;
    }

    public Instant getActivatedAt() {
        return activatedAt;
    }

    public Instant getPausedAt() {
        return pausedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public Instant getLastLoadedAt() {
        return lastLoadedAt;
    }

    public int getEntriesLoaded() {
        return entriesLoaded;
    }
}
