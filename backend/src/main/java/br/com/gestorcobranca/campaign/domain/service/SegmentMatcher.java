package br.com.gestorcobranca.campaign.domain.service;

import br.com.gestorcobranca.campaign.domain.model.AudienceSnapshot;
import br.com.gestorcobranca.campaign.domain.model.SegmentCriteria;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Avaliação em memória de um segmento sobre a posição de um caso — a mesma semântica da consulta SQL
 * do read model ({@code JdbcAudienceStore}). Devolve os critérios não atendidos (vazio = atende),
 * usados como detalhe quando a régua pula etapas ou encerra a inscrição por saída do segmento.
 */
public final class SegmentMatcher {

    private SegmentMatcher() {
    }

    /**
     * @param creditorId  credor exigido (o da campanha; nulo = qualquer)
     * @param portfolioId carteira exigida (a da campanha; nula = qualquer)
     */
    public static List<String> unmet(SegmentCriteria c, UUID creditorId, UUID portfolioId, AudienceSnapshot s,
                                     Instant now, ZoneId zone) {
        List<String> unmet = new ArrayList<>();
        LocalDate today = now.atZone(zone).toLocalDate();
        if (!s.active()) {
            unmet.add("caseActive");
            return unmet;
        }
        check(unmet, "creditor", creditorId == null || creditorId.equals(s.creditorId()));
        check(unmet, "portfolio", portfolioId == null || portfolioId.equals(s.portfolioId()));
        check(unmet, "portfolioIds", c.portfolioIds().isEmpty() || c.portfolioIds().contains(s.portfolioId()));
        long days = s.daysOverdue(today);
        check(unmet, "minDaysOverdue", c.minDaysOverdue() == null || days >= c.minDaysOverdue());
        check(unmet, "maxDaysOverdue", c.maxDaysOverdue() == null || days <= c.maxDaysOverdue());
        check(unmet, "minBalance", c.minBalance() == null || s.openBalance().compareTo(c.minBalance()) >= 0);
        check(unmet, "maxBalance", c.maxBalance() == null || s.openBalance().compareTo(c.maxBalance()) <= 0);
        check(unmet, "caseStatuses", c.caseStatuses().isEmpty() || c.caseStatuses().contains(s.status()));
        check(unmet, "eligibleOnly", !Boolean.TRUE.equals(c.eligibleOnly()) || s.eligible());
        check(unmet, "contactableChannel", c.contactableChannel() == null
                || s.contactableChannels().contains(c.contactableChannel()));
        check(unmet, "minInteractions", c.minInteractions() == null || s.interactionCount() >= c.minInteractions());
        check(unmet, "maxInteractions", c.maxInteractions() == null || s.interactionCount() <= c.maxInteractions());
        if (c.everEffectiveContact() != null) {
            check(unmet, "everEffectiveContact", c.everEffectiveContact() == (s.effectiveContactCount() > 0));
        }
        check(unmet, "noInteractionDays", c.noInteractionDays() == null
                || olderThan(s.lastInteractionAt(), now, c.noInteractionDays()));
        check(unmet, "noEffectiveContactDays", c.noEffectiveContactDays() == null
                || olderThan(s.lastEffectiveContactAt(), now, c.noEffectiveContactDays()));
        if (c.pendingPromise() != null) {
            check(unmet, "pendingPromise", c.pendingPromise() == s.pendingPromise());
        }
        if (c.activeAgreement() != null) {
            check(unmet, "activeAgreement", c.activeAgreement() == s.activeAgreement());
        }
        check(unmet, "minBrokenAgreements", c.minBrokenAgreements() == null
                || s.brokenAgreements() >= c.minBrokenAgreements());
        check(unmet, "minScore", c.minScore() == null || (s.score() != null && s.score() >= c.minScore()));
        check(unmet, "maxScore", c.maxScore() == null || (s.score() != null && s.score() <= c.maxScore()));
        check(unmet, "ownerTypes", c.ownerTypes().isEmpty() || c.ownerTypes().contains(s.ownerType()));
        Long toInstallment = s.daysUntilNextInstallment(today);
        check(unmet, "minDaysToInstallment", c.minDaysToInstallment() == null
                || (toInstallment != null && toInstallment >= c.minDaysToInstallment()));
        check(unmet, "maxDaysToInstallment", c.maxDaysToInstallment() == null
                || (toInstallment != null && toInstallment >= 0 && toInstallment <= c.maxDaysToInstallment()));
        return unmet;
    }

    public static boolean matches(SegmentCriteria c, UUID creditorId, UUID portfolioId, AudienceSnapshot s, Instant now,
                                  ZoneId zone) {
        return unmet(c, creditorId, portfolioId, s, now, zone).isEmpty();
    }

    /** Sem registro nos últimos N dias (nunca também atende). */
    private static boolean olderThan(Instant last, Instant now, int days) {
        return last == null || last.isBefore(now.minus(Duration.ofDays(days)));
    }

    private static void check(List<String> unmet, String criterion, boolean ok) {
        if (!ok) {
            unmet.add(criterion);
        }
    }
}
