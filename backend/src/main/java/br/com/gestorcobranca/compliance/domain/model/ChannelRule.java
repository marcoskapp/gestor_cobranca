package br.com.gestorcobranca.compliance.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.time.Duration;
import java.time.LocalTime;
import java.util.Optional;

/**
 * Regra de um canal numa versão de política de contato. Campos nulos não restringem; a
 * janela nula herda a janela padrão da assessoria.
 */
@Embeddable
public class ChannelRule {

    private static final int MAX_LIMIT = 1_000;
    private static final int MAX_INTERVAL_MINUTES = 60 * 24 * 30;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20)
    private ContactChannel channel;

    @Column(name = "window_start")
    private LocalTime windowStart;

    @Column(name = "window_end")
    private LocalTime windowEnd;

    /** Dias da janela na forma canônica de {@link WeekDays}. */
    @Column(name = "allowed_days", length = 27)
    private String allowedDays;

    @Column(name = "max_per_day")
    private Integer maxPerDay;

    @Column(name = "max_per_week")
    private Integer maxPerWeek;

    @Column(name = "min_interval_minutes")
    private Integer minIntervalMinutes;

    @Column(name = "block_on_holidays", nullable = false)
    private boolean blockOnHolidays;

    protected ChannelRule() {
    }

    /**
     * @param window janela própria do canal (opcional; nula herda a da assessoria)
     */
    public ChannelRule(ContactChannel channel, ContactWindow window, Integer maxPerDay, Integer maxPerWeek,
                       Integer minIntervalMinutes, boolean blockOnHolidays) {
        Guard.require(channel != null, "contact_policy.channel_required", "Canal obrigatório");
        Guard.require(maxPerDay == null || maxPerDay > 0 && maxPerDay <= MAX_LIMIT, "contact_policy.day_limit_invalid",
                "Limite diário deve estar entre 1 e %d".formatted(MAX_LIMIT));
        Guard.require(maxPerWeek == null || maxPerWeek > 0 && maxPerWeek <= MAX_LIMIT, "contact_policy.week_limit_invalid",
                "Limite semanal deve estar entre 1 e %d".formatted(MAX_LIMIT));
        Guard.require(maxPerDay == null || maxPerWeek == null || maxPerWeek >= maxPerDay,
                "contact_policy.week_limit_below_day", "Limite semanal não pode ser menor que o diário");
        Guard.require(minIntervalMinutes == null || minIntervalMinutes > 0 && minIntervalMinutes <= MAX_INTERVAL_MINUTES,
                "contact_policy.interval_invalid", "Intervalo mínimo deve estar entre 1 minuto e 30 dias");
        this.channel = channel;
        if (window != null) {
            this.windowStart = window.start();
            this.windowEnd = window.end();
            this.allowedDays = WeekDays.format(window.days());
        }
        this.maxPerDay = maxPerDay;
        this.maxPerWeek = maxPerWeek;
        this.minIntervalMinutes = minIntervalMinutes;
        this.blockOnHolidays = blockOnHolidays;
    }

    public ContactChannel getChannel() {
        return channel;
    }

    /** Janela própria do canal; vazia quando herda a da assessoria. */
    public Optional<ContactWindow> window() {
        return windowStart == null ? Optional.empty() : Optional.of(new ContactWindow(windowStart, windowEnd, WeekDays.parse(allowedDays)));
    }

    public Integer getMaxPerDay() {
        return maxPerDay;
    }

    public Integer getMaxPerWeek() {
        return maxPerWeek;
    }

    public Optional<Duration> minInterval() {
        return minIntervalMinutes == null ? Optional.empty() : Optional.of(Duration.ofMinutes(minIntervalMinutes));
    }

    public Integer getMinIntervalMinutes() {
        return minIntervalMinutes;
    }

    public boolean isBlockOnHolidays() {
        return blockOnHolidays;
    }
}
