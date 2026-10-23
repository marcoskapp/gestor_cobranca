package br.com.gestorcobranca.compliance.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Registro da base legal do tratamento dos dados dos devedores de um credor (RB09): base do
 * art. 7º, papel da assessoria, referência contratual, responsável jurídico, vigência e prazo
 * de retenção exigido. Pode ser específico de uma carteira, prevalecendo sobre o do credor.
 * Vigências do mesmo alcance não se sobrepõem (restrição de exclusão no banco).
 */
@Entity
@Table(name = "legal_basis_record", schema = "compliance")
public class LegalBasisRecord extends TenantScopedEntity {

    private static final int MAX_RETENTION_MONTHS = 1_200;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "portfolio_id", updatable = false)
    private UUID portfolioId;

    @Enumerated(EnumType.STRING)
    @Column(name = "legal_basis", nullable = false, length = 40, updatable = false)
    private LegalBasis legalBasis;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_role", nullable = false, length = 20, updatable = false)
    private ProcessingRole processingRole;

    @Column(name = "contract_reference", nullable = false, length = 120, updatable = false)
    private String contractReference;

    @Column(name = "valid_from", nullable = false, updatable = false)
    private LocalDate validFrom;

    @Column(name = "valid_until")
    private LocalDate validUntil;

    @Column(name = "legal_responsible", nullable = false, length = 150, updatable = false)
    private String legalResponsible;

    @Column(name = "retention_months", nullable = false, updatable = false)
    private int retentionMonths;

    @Column(name = "notes", length = 1000, updatable = false)
    private String notes;

    @Column(name = "end_reason", length = 500)
    private String endReason;

    protected LegalBasisRecord() {
    }

    /** @param processingRole nulo assume {@link ProcessingRole#OPERADOR} */
    public LegalBasisRecord(UUID creditorId, UUID portfolioId, LegalBasis legalBasis, ProcessingRole processingRole,
                            String contractReference, LocalDate validFrom, LocalDate validUntil, String legalResponsible,
                            int retentionMonths, String notes) {
        Guard.require(creditorId != null, "legal_basis.creditor_required", "Credor obrigatório");
        Guard.require(legalBasis != null, "legal_basis.basis_required", "Base legal obrigatória");
        Guard.require(validFrom != null, "legal_basis.valid_from_required", "Início da vigência obrigatório");
        Guard.require(validUntil == null || !validUntil.isBefore(validFrom), "legal_basis.period_invalid",
                "Fim da vigência não pode ser anterior ao início");
        Guard.require(retentionMonths > 0 && retentionMonths <= MAX_RETENTION_MONTHS, "legal_basis.retention_invalid",
                "Prazo de retenção deve estar entre 1 e %d meses".formatted(MAX_RETENTION_MONTHS));
        Guard.require(notes == null || notes.length() <= 1000, "legal_basis.notes_too_long", "Observações muito longas");
        this.creditorId = creditorId;
        this.portfolioId = portfolioId;
        this.legalBasis = legalBasis;
        this.processingRole = processingRole == null ? ProcessingRole.OPERADOR : processingRole;
        this.contractReference = requireText(contractReference, 120, "legal_basis.contract_reference_required");
        this.validFrom = validFrom;
        this.validUntil = validUntil;
        this.legalResponsible = requireText(legalResponsible, 150, "legal_basis.legal_responsible_required");
        this.retentionMonths = retentionMonths;
        this.notes = notes == null || notes.isBlank() ? null : notes.trim();
    }

    public boolean isEffectiveOn(LocalDate date) {
        return !date.isBefore(validFrom) && (validUntil == null || !date.isAfter(validUntil));
    }

    /** Encerra a vigência (ex.: fim do contrato com o credor), preservando o registro. */
    public void end(LocalDate lastDay, String reason) {
        Guard.requireState(validUntil == null || lastDay.isBefore(validUntil), "legal_basis.already_ended",
                "Vigência já encerrada nesta data ou antes");
        Guard.require(lastDay != null && !lastDay.isBefore(validFrom), "legal_basis.period_invalid",
                "Fim da vigência não pode ser anterior ao início");
        this.validUntil = lastDay;
        this.endReason = requireText(reason, 500, "legal_basis.end_reason_required");
    }

    private static String requireText(String value, int max, String code) {
        Guard.require(value != null && !value.isBlank(), code, "Campo obrigatório");
        String trimmed = value.trim();
        Guard.require(trimmed.length() <= max, code, "Campo deve ter até %d caracteres".formatted(max));
        return trimmed;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public UUID getPortfolioId() {
        return portfolioId;
    }

    public LegalBasis getLegalBasis() {
        return legalBasis;
    }

    public ProcessingRole getProcessingRole() {
        return processingRole;
    }

    public String getContractReference() {
        return contractReference;
    }

    public LocalDate getValidFrom() {
        return validFrom;
    }

    public LocalDate getValidUntil() {
        return validUntil;
    }

    public String getLegalResponsible() {
        return legalResponsible;
    }

    public int getRetentionMonths() {
        return retentionMonths;
    }

    public String getNotes() {
        return notes;
    }

    public String getEndReason() {
        return endReason;
    }
}
