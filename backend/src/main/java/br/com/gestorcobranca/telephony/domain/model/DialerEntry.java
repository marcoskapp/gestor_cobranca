package br.com.gestorcobranca.telephony.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Caso na lista de uma campanha de discagem: tentativas, próxima tentativa e último resultado.
 * Negação temporária do porteiro (horário, feriado, limites) adia sem consumir tentativa;
 * restrição sem fim previsto exclui o caso da lista.
 */
@Entity
@Table(name = "dialer_entry", schema = "telephony")
public class DialerEntry extends TenantScopedEntity {

    @Column(name = "campaign_id", nullable = false, updatable = false)
    private UUID campaignId;

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Column(name = "debtor_id", nullable = false, updatable = false)
    private UUID debtorId;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "portfolio_id", updatable = false)
    private UUID portfolioId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DialerEntryStatus status;

    @Column(name = "priority", nullable = false, updatable = false)
    private int priority;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_call_id")
    private UUID lastCallId;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_result", length = 20)
    private CallResult lastResult;

    @Column(name = "last_attempt_at")
    private Instant lastAttemptAt;

    @Column(name = "last_denial", length = 300)
    private String lastDenial;

    @Column(name = "excluded_reason", length = 60)
    private String excludedReason;

    @Column(name = "finished_at")
    private Instant finishedAt;

    protected DialerEntry() {
    }

    public DialerEntry(UUID campaignId, UUID caseId, UUID debtorId, UUID creditorId, UUID portfolioId, int priority,
                       Instant now) {
        Guard.require(campaignId != null && caseId != null && debtorId != null && creditorId != null,
                "dialer_entry.required", "Campanha, caso, devedor e credor obrigatórios");
        this.campaignId = campaignId;
        this.caseId = caseId;
        this.debtorId = debtorId;
        this.creditorId = creditorId;
        this.portfolioId = portfolioId;
        this.priority = priority;
        this.status = DialerEntryStatus.PENDING;
        this.nextAttemptAt = now;
    }

    /** Tentativa iniciada (chamada criada). */
    public void attemptStarted(UUID callId, Instant now) {
        Guard.requireState(status == DialerEntryStatus.PENDING, "dialer_entry.not_pending", "Item não está pendente");
        this.status = DialerEntryStatus.IN_PROGRESS;
        this.attempts++;
        this.lastCallId = callId;
        this.lastAttemptAt = now;
        this.lastDenial = null;
    }

    /** Porteiro negou temporariamente: próxima tentativa no primeiro instante permitido, sem consumir tentativa. */
    public void defer(String reasons, Instant until) {
        Guard.requireState(status == DialerEntryStatus.PENDING, "dialer_entry.not_pending", "Item não está pendente");
        this.lastDenial = reasons == null || reasons.length() <= 300 ? reasons : reasons.substring(0, 300);
        this.nextAttemptAt = until;
    }

    public void exclude(String reason, Instant now) {
        if (status == DialerEntryStatus.COMPLETED || status == DialerEntryStatus.EXHAUSTED
                || status == DialerEntryStatus.EXCLUDED) {
            return;
        }
        this.status = DialerEntryStatus.EXCLUDED;
        this.excludedReason = reason == null || reason.length() <= 60 ? reason : reason.substring(0, 60);
        this.finishedAt = now;
    }

    /**
     * Resultado da tentativa aplicado pela política de retentativas.
     *
     * @return false se a chamada não é a tentativa corrente do item (evento atrasado)
     */
    public boolean attemptFinished(UUID callId, CallResult result, DialerEntryStatus next, Instant nextAttemptAt,
                                   Instant now) {
        if (status != DialerEntryStatus.IN_PROGRESS || !callId.equals(lastCallId)) {
            return false;
        }
        this.lastResult = result;
        this.status = next;
        if (next == DialerEntryStatus.PENDING) {
            this.nextAttemptAt = nextAttemptAt;
        } else {
            this.finishedAt = now;
        }
        return true;
    }

    public UUID getCampaignId() {
        return campaignId;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public UUID getDebtorId() {
        return debtorId;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public UUID getPortfolioId() {
        return portfolioId;
    }

    public DialerEntryStatus getStatus() {
        return status;
    }

    public int getPriority() {
        return priority;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public UUID getLastCallId() {
        return lastCallId;
    }

    public CallResult getLastResult() {
        return lastResult;
    }

    public Instant getLastAttemptAt() {
        return lastAttemptAt;
    }

    public String getLastDenial() {
        return lastDenial;
    }

    public String getExcludedReason() {
        return excludedReason;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }
}
