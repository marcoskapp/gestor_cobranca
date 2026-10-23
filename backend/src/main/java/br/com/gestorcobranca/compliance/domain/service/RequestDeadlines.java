package br.com.gestorcobranca.compliance.domain.service;

import br.com.gestorcobranca.compliance.domain.model.RequestType;
import br.com.gestorcobranca.shared.domain.Guard;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Prazo de atendimento de solicitações de titulares em dias corridos (LGPD art. 19, II: 15 dias
 * para a declaração completa de acesso/confirmação). O prazo termina ao fim do último dia, no
 * fuso de operação: recebida em 03/03 às 10h com 15 dias, vence ao fim de 18/03.
 */
public final class RequestDeadlines {

    private final Map<RequestType, Integer> daysByType;
    private final int defaultDays;
    private final ZoneId zone;

    public RequestDeadlines(Map<RequestType, Integer> daysByType, int defaultDays, ZoneId zone) {
        Guard.require(defaultDays > 0, "dsr.due_days_invalid", "Prazo padrão deve ser positivo");
        this.daysByType = new EnumMap<>(RequestType.class);
        if (daysByType != null) {
            daysByType.forEach((type, days) -> {
                Guard.require(days != null && days > 0, "dsr.due_days_invalid", "Prazo de %s deve ser positivo".formatted(type));
                this.daysByType.put(type, days);
            });
        }
        this.defaultDays = defaultDays;
        this.zone = Objects.requireNonNull(zone, "zone");
    }

    public int daysFor(RequestType type) {
        return daysByType.getOrDefault(type, defaultDays);
    }

    /** Instante limite (exclusivo): início do dia seguinte ao último dia do prazo. */
    public Instant dueAt(RequestType type, Instant receivedAt) {
        LocalDate received = LocalDate.ofInstant(receivedAt, zone);
        return received.plusDays(daysFor(type) + 1L).atStartOfDay(zone).toInstant();
    }
}
