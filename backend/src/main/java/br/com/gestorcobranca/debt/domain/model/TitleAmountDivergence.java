package br.com.gestorcobranca.debt.domain.model;

import br.com.gestorcobranca.shared.crypto.EncryptedStringConverter;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.Money;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Carga do credor com principal diferente do corrigido manualmente pela assessoria. A carga não sobrescreve a correção:
 * a divergência fica aberta (uma por título) até alguém manter a correção, aceitar o valor do credor ou a carga
 * seguinte trazer o valor corrigido.
 */
@Entity
@Table(name = "title_amount_divergence", schema = "debt")
public class TitleAmountDivergence extends TenantScopedEntity {

    @Column(name = "title_id", nullable = false, updatable = false)
    private UUID titleId;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 25)
    private AmountDivergenceStatus status;

    @Column(name = "current_principal", nullable = false, precision = 19, scale = 2, updatable = false)
    private Money currentPrincipal;

    @Column(name = "reported_principal", nullable = false, precision = 19, scale = 2, updatable = false)
    private Money reportedPrincipal;

    @Column(name = "import_batch_id")
    private UUID importBatchId;

    @Column(name = "detected_at", nullable = false, updatable = false)
    private Instant detectedAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    @Column(name = "occurrences", nullable = false)
    private int occurrences;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "resolved_by_name", length = 200)
    private String resolvedByName;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "resolution_note_encrypted", length = 24000)
    private String resolutionNote;

    protected TitleAmountDivergence() {
    }

    public TitleAmountDivergence(DebtTitle title, Money reportedPrincipal, UUID importBatchId, Instant now) {
        Guard.require(!reportedPrincipal.equals(title.getPrincipalAmount()), "title_amount.divergence_equal",
                "Valor informado igual ao atual não é divergência");
        this.titleId = title.getId();
        this.creditorId = title.getCreditorId();
        this.status = AmountDivergenceStatus.OPEN;
        this.currentPrincipal = title.getPrincipalAmount();
        this.reportedPrincipal = reportedPrincipal;
        this.importBatchId = importBatchId;
        this.detectedAt = now;
        this.lastSeenAt = now;
        this.occurrences = 1;
    }

    public boolean isOpen() {
        return status == AmountDivergenceStatus.OPEN;
    }

    /** A mesma divergência veio de novo numa carga. */
    public void seenAgain(UUID importBatchId, Instant now) {
        Guard.requireState(isOpen(), "title_amount.divergence_closed", "Divergência já resolvida");
        this.importBatchId = importBatchId;
        this.lastSeenAt = now;
        this.occurrences++;
    }

    /** Encerra por decisão de alguém (com motivo) ou pela própria carga (sem autor). */
    public void close(AmountDivergenceStatus outcome, UUID by, String byName, String note, Instant now) {
        Guard.requireState(isOpen(), "title_amount.divergence_closed", "Divergência já resolvida");
        Guard.require(outcome != null && outcome != AmountDivergenceStatus.OPEN, "title_amount.divergence_outcome_invalid",
                "Resultado inválido");
        boolean byPerson = outcome == AmountDivergenceStatus.KEPT_MANUAL || outcome == AmountDivergenceStatus.ACCEPTED_CREDITOR;
        if (byPerson) {
            Guard.require(note != null && !note.isBlank() && note.trim().length() <= TitleAmountAdjustment.NOTE_MAX,
                    "title_amount.divergence_note_required", "Informe o motivo da decisão");
        }
        this.status = outcome;
        this.resolvedBy = by;
        this.resolvedByName = byName == null || byName.length() <= 200 ? byName : byName.substring(0, 200);
        this.resolvedAt = now;
        this.resolutionNote = note == null || note.isBlank() ? null : note.trim();
    }

    public UUID getTitleId() {
        return titleId;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public AmountDivergenceStatus getStatus() {
        return status;
    }

    public Money getCurrentPrincipal() {
        return currentPrincipal;
    }

    public Money getReportedPrincipal() {
        return reportedPrincipal;
    }

    public UUID getImportBatchId() {
        return importBatchId;
    }

    public Instant getDetectedAt() {
        return detectedAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public int getOccurrences() {
        return occurrences;
    }

    public UUID getResolvedBy() {
        return resolvedBy;
    }

    public String getResolvedByName() {
        return resolvedByName;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public String getResolutionNote() {
        return resolutionNote;
    }
}
