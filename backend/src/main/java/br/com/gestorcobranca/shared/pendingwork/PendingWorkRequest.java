package br.com.gestorcobranca.shared.pendingwork;

import br.com.gestorcobranca.shared.security.Actor;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;

/**
 * Contexto de uma consulta de pendências, com o mesmo "agora" para todas as fontes.
 *
 * @param actor           usuário da sessão
 * @param now             instante da consulta
 * @param today           data de negócio (fuso de operação)
 * @param startOfToday    início do dia de negócio
 * @param startOfTomorrow início do dia seguinte (limite de "vence hoje" para prazos com hora)
 * @param dueSoonDays     janela de "perto do prazo" em dias corridos para fontes que só listam o que
 *                        vence em breve (ex.: solicitações de titulares, prazos jurídicos)
 */
public record PendingWorkRequest(Actor actor, Instant now, LocalDate today, Instant startOfToday,
                                 Instant startOfTomorrow, int dueSoonDays) {

    public PendingWorkRequest {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(today, "today");
        Objects.requireNonNull(startOfToday, "startOfToday");
        Objects.requireNonNull(startOfTomorrow, "startOfTomorrow");
        if (dueSoonDays < 0) {
            throw new IllegalArgumentException("dueSoonDays");
        }
    }

    /** Último dia da janela "perto do prazo" (inclusive). */
    public LocalDate dueSoonUntil() {
        return today.plusDays(dueSoonDays);
    }
}
