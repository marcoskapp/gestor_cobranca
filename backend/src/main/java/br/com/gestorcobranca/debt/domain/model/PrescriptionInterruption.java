package br.com.gestorcobranca.debt.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Interrupção da prescrição de um título (append-only: o banco recusa UPDATE/DELETE). Registra o
 * ato, a data, a referência (protocolo do protesto, número do processo) e, opcionalmente, a
 * evidência armazenada no {@code FileStorage}. Se a interrupção é eficaz é decidido no cálculo
 * ({@code PrescriptionCalculator}), conforme a regra vigente — o registro do fato nunca muda.
 * <p>
 * Há no máximo uma interrupção por (título, tipo, data).
 */
@Entity
@Immutable
@Table(name = "prescription_interruption", schema = "debt")
public class PrescriptionInterruption extends TenantScopedEntity {

    @Column(name = "title_id", nullable = false, updatable = false)
    private UUID titleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20, updatable = false)
    private InterruptionType type;

    @Column(name = "interruption_date", nullable = false, updatable = false)
    private LocalDate interruptionDate;

    @Column(name = "reference", length = 200, updatable = false)
    private String reference;

    @Column(name = "notes", length = 500, updatable = false)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, length = 20, updatable = false)
    private InterruptionOrigin origin;

    @Column(name = "origin_reference_id", updatable = false)
    private UUID originReferenceId;

    @Column(name = "evidence_key", length = 512, updatable = false)
    private String evidenceKey;

    @Column(name = "evidence_name", length = 200, updatable = false)
    private String evidenceName;

    @Column(name = "evidence_content_type", length = 100, updatable = false)
    private String evidenceContentType;

    @Column(name = "evidence_size", updatable = false)
    private Long evidenceSize;

    @Column(name = "evidence_sha256", length = 64, updatable = false)
    private String evidenceSha256;

    protected PrescriptionInterruption() {
    }

    /**
     * @param originReferenceId registro de origem no módulo jurídico (medida, andamento); nulo em registro manual
     */
    public PrescriptionInterruption(UUID titleId, InterruptionType type, LocalDate interruptionDate, String reference,
                                    String notes, InterruptionOrigin origin, UUID originReferenceId, Evidence evidence) {
        this.titleId = Objects.requireNonNull(titleId, "titleId");
        Guard.require(type != null, "prescription_interruption.type_required", "Tipo de interrupção obrigatório");
        Guard.require(interruptionDate != null, "prescription_interruption.date_required", "Data da interrupção obrigatória");
        Guard.require(origin != null, "prescription_interruption.origin_required", "Origem obrigatória");
        this.type = type;
        this.interruptionDate = interruptionDate;
        this.reference = optional(reference, 200, "prescription_interruption.reference_too_long", "Referência");
        this.notes = optional(notes, 500, "prescription_interruption.notes_too_long", "Observação");
        Guard.require(type != InterruptionType.OTHER || this.notes != null || this.reference != null,
                "prescription_interruption.description_required", "Descreva o ato que interrompeu a prescrição");
        this.origin = origin;
        this.originReferenceId = originReferenceId;
        if (evidence != null) {
            this.evidenceKey = evidence.key();
            this.evidenceName = evidence.name();
            this.evidenceContentType = evidence.contentType();
            this.evidenceSize = evidence.size();
            this.evidenceSha256 = evidence.sha256();
        }
    }

    private static String optional(String value, int max, String code, String label) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        Guard.require(trimmed.length() <= max, code, "%s deve ter até %d caracteres".formatted(label, max));
        return trimmed;
    }

    /** Arquivo de evidência já gravado no armazenamento do tenant. */
    public record Evidence(String key, String name, String contentType, long size, String sha256) {

        public Evidence {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(sha256, "sha256");
            name = name == null || name.isBlank() ? "evidencia" : name.length() > 200 ? name.substring(name.length() - 200) : name;
        }
    }

    public java.util.Optional<Evidence> evidence() {
        return evidenceKey == null ? java.util.Optional.empty()
                : java.util.Optional.of(new Evidence(evidenceKey, evidenceName, evidenceContentType, evidenceSize, evidenceSha256));
    }

    public UUID getTitleId() {
        return titleId;
    }

    public InterruptionType getType() {
        return type;
    }

    public LocalDate getInterruptionDate() {
        return interruptionDate;
    }

    public String getReference() {
        return reference;
    }

    public String getNotes() {
        return notes;
    }

    public InterruptionOrigin getOrigin() {
        return origin;
    }

    public UUID getOriginReferenceId() {
        return originReferenceId;
    }
}
