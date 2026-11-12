package br.com.gestorcobranca.campaign.domain.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Posição de um caso de cobrança no read model de audiência do módulo (projeção de
 * {@code CaseUseCases}, sem dado pessoal). Base da avaliação de segmentos e das pausas.
 *
 * @param brokenAgreements acordos do caso já quebrados (contados pelo consumo de {@code AgreementStatusChanged})
 * @param caseVersion      versão do caso no collection (a projeção nunca regride)
 * @param nextInstallmentDueDate vencimento da próxima parcela em aberto do acordo em vigor do caso (negotiation);
 *                         nulo sem acordo em vigor ou sem parcela em aberto
 * @param newContactUntil  fim da novidade dos contatos do devedor recebidos na carga (debt, evento
 *                         {@code DebtorContactNoveltyChanged}); nulo = nenhum contato novo pendente
 * @param debtorPersonType tipo de pessoa do devedor (INDIVIDUAL/COMPANY), do caso no collection; nulo = desconhecido
 */
public record AudienceSnapshot(UUID caseId, UUID debtorId, UUID creditorId, UUID portfolioId, String status,
                               String resumeStatus, boolean eligible, List<String> eligibilityReasons,
                               List<String> contactableChannels, BigDecimal openBalance, BigDecimal eligibleBalance,
                               LocalDate oldestDueDate, String ownerType, int interactionCount,
                               int effectiveContactCount, Instant lastInteractionAt, Instant lastEffectiveContactAt,
                               Integer score, int brokenAgreements, long caseVersion, Instant refreshedAt,
                               LocalDate nextInstallmentDueDate, Instant newContactUntil, String debtorPersonType) {

    public AudienceSnapshot {
        eligibilityReasons = eligibilityReasons == null ? List.of() : List.copyOf(eligibilityReasons);
        contactableChannels = contactableChannels == null ? List.of() : List.copyOf(contactableChannels);
    }

    public boolean active() {
        return !"CLOSED".equals(status);
    }

    /** Dias desde o vencimento mais antigo em cobrança (0 sem vencimento ou vencimento futuro). */
    public long daysOverdue(LocalDate today) {
        return oldestDueDate == null ? 0 : Math.max(0, ChronoUnit.DAYS.between(oldestDueDate, today));
    }

    /** Promessa pendente: o estágio (ou o estágio guardado durante uma retenção) é PROMISE. */
    public boolean pendingPromise() {
        return "PROMISE".equals(status) || "PROMISE".equals(resumeStatus);
    }

    /** Acordo em vigor: o estágio (ou o guardado) é AGREEMENT. */
    public boolean activeAgreement() {
        return "AGREEMENT".equals(status) || "AGREEMENT".equals(resumeStatus);
    }

    /**
     * Dias de hoje até o vencimento da próxima parcela em aberto do acordo (0 = vence hoje; negativo =
     * vencida); nulo sem acordo em vigor com parcela em aberto.
     */
    public Long daysUntilNextInstallment(LocalDate today) {
        return nextInstallmentDueDate == null ? null : ChronoUnit.DAYS.between(today, nextInstallmentDueDate);
    }

    public AudienceSnapshot withBrokenAgreements(int count) {
        return new AudienceSnapshot(caseId, debtorId, creditorId, portfolioId, status, resumeStatus, eligible,
                eligibilityReasons, contactableChannels, openBalance, eligibleBalance, oldestDueDate, ownerType,
                interactionCount, effectiveContactCount, lastInteractionAt, lastEffectiveContactAt, score, count,
                caseVersion, refreshedAt, nextInstallmentDueDate, newContactUntil, debtorPersonType);
    }

    public AudienceSnapshot withNextInstallmentDueDate(LocalDate dueDate) {
        return new AudienceSnapshot(caseId, debtorId, creditorId, portfolioId, status, resumeStatus, eligible,
                eligibilityReasons, contactableChannels, openBalance, eligibleBalance, oldestDueDate, ownerType,
                interactionCount, effectiveContactCount, lastInteractionAt, lastEffectiveContactAt, score,
                brokenAgreements, caseVersion, refreshedAt, dueDate, newContactUntil, debtorPersonType);
    }

    public AudienceSnapshot withNewContactUntil(Instant until) {
        return new AudienceSnapshot(caseId, debtorId, creditorId, portfolioId, status, resumeStatus, eligible,
                eligibilityReasons, contactableChannels, openBalance, eligibleBalance, oldestDueDate, ownerType,
                interactionCount, effectiveContactCount, lastInteractionAt, lastEffectiveContactAt, score,
                brokenAgreements, caseVersion, refreshedAt, nextInstallmentDueDate, until, debtorPersonType);
    }

    /** O devedor tem contato novo recebido na carga ainda sem tentativa agora. */
    public boolean hasNewContact(Instant now) {
        return newContactUntil != null && newContactUntil.isAfter(now);
    }
}
