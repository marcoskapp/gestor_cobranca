package br.com.gestorcobranca.debt.domain.model;

import br.com.gestorcobranca.shared.crypto.EncryptedStringConverter;
import br.com.gestorcobranca.shared.domain.Document;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * Representante da empresa devedora (cobrança B2B, B3): quem pode negociar em nome dela — sócio administrador,
 * procurador ou outro. <strong>Não é devedor</strong> (não responde pela dívida, ao contrário do coobrigado): fica fora
 * de {@code debt.debtor} e, portanto, de listas, buscas, filas, segmentos, score e réguas.
 * <p>
 * Dados mínimos (LGPD): nome e CPF criptografados, papel. {@code documentHash} é o blind index do CPF com finalidade
 * própria ({@link #DOCUMENT_HASH_PURPOSE}), usado na confirmação do representante no portal e na solicitação de titular.
 * Encerrado, nunca alterado no CPF: outro CPF é outro representante.
 */
@Entity
@Table(name = "company_representative", schema = "debt")
public class CompanyRepresentative extends TenantScopedEntity {

    /** Finalidade do blind index do CPF do representante (diferente da do devedor). */
    public static final String DOCUMENT_HASH_PURPOSE = "debt.representative.document";
    static final int NAME_MAX = 200;
    static final int EVIDENCE_MAX = 200;
    static final int END_REASON_MAX = 300;

    public enum Source { MANUAL, IMPORT }

    @Column(name = "company_debtor_id", nullable = false, updatable = false)
    private UUID companyDebtorId;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "document_encrypted", nullable = false, length = 512, updatable = false)
    private String document;

    @Column(name = "document_hash", nullable = false, length = 64, updatable = false)
    private String documentHash;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "name_encrypted", nullable = false, length = 1024)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private RepresentativeRole role;

    @Column(name = "can_negotiate", nullable = false)
    private boolean canNegotiate;

    @Column(name = "valid_until")
    private LocalDate validUntil;

    @Column(name = "evidence_note", length = 200)
    private String evidenceNote;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20, updatable = false)
    private Source source;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "end_reason", length = 300)
    private String endReason;

    protected CompanyRepresentative() {
    }

    /**
     * @param company      devedor pessoa jurídica ativo ({@code representative.company_required})
     * @param document     CPF do representante ({@code representative.cpf_required})
     * @param documentHash blind index do CPF com finalidade {@link #DOCUMENT_HASH_PURPOSE}
     */
    public CompanyRepresentative(Debtor company, Document document, String documentHash, String name,
                                 RepresentativeRole role, boolean canNegotiate, LocalDate validUntil,
                                 String evidenceNote, Source source) {
        Objects.requireNonNull(company, "company");
        Guard.require(company.isCompany(), "representative.company_required",
                "Representantes só existem para devedor pessoa jurídica");
        Guard.requireState(!company.isAnonymized(), "debtor.anonymized", "Devedor anonimizado não recebe representantes");
        Guard.require(document != null && document.type() == Document.DocumentType.CPF, "representative.cpf_required",
                "O representante é uma pessoa física: informe o CPF");
        Guard.require(!document.equals(company.document().orElse(null)), "representative.cpf_required",
                "O representante é uma pessoa física: informe o CPF");
        Guard.require(documentHash != null && !documentHash.isBlank(), "representative.hash_required",
                "Índice do documento obrigatório");
        Guard.require(source != null, "representative.source_required", "Origem obrigatória");
        this.companyDebtorId = company.getId();
        this.document = document.number();
        this.documentHash = documentHash;
        this.source = source;
        update(name, role, canNegotiate, validUntil, evidenceNote);
    }

    /** Altera nome, papel, poder para negociar, validade e evidência (o CPF não muda). */
    public void update(String name, RepresentativeRole role, boolean canNegotiate, LocalDate validUntil,
                       String evidenceNote) {
        requireActive();
        Guard.require(name != null && !name.isBlank(), "representative.name_required", "Nome do representante obrigatório");
        String cleanName = name.trim().replaceAll("\\s+", " ");
        Guard.require(cleanName.length() <= NAME_MAX, "representative.name_too_long", "Nome do representante muito longo");
        Guard.require(role != null, "representative.role_required", "Papel do representante obrigatório");
        String note = evidenceNote == null || evidenceNote.isBlank() ? null : evidenceNote.trim();
        Guard.require(note == null || note.length() <= EVIDENCE_MAX, "representative.evidence_too_long",
                "Evidência muito longa (até %d caracteres)".formatted(EVIDENCE_MAX));
        this.name = cleanName;
        this.role = role;
        this.canNegotiate = canNegotiate;
        this.validUntil = validUntil;
        this.evidenceNote = note;
    }

    /** Encerramento (saiu da sociedade, procuração revogada): o registro fica para a trilha. */
    public void end(String reason, Instant now) {
        requireActive();
        Guard.require(reason != null && !reason.isBlank(), "representative.end_reason_required",
                "Informe o motivo do encerramento");
        Guard.require(reason.trim().length() <= END_REASON_MAX, "representative.end_reason_too_long", "Motivo muito longo");
        this.endedAt = now;
        this.endReason = reason.trim();
    }

    public boolean isActive() {
        return endedAt == null;
    }

    /** Vigente e com validade até {@code today} (inclusive) — sem validade, vale até o encerramento. */
    public boolean isCurrentOn(LocalDate today) {
        return isActive() && (validUntil == null || !validUntil.isBefore(today));
    }

    /** Pode negociar pela empresa hoje: vigente, dentro da validade e com poder para negociar. */
    public boolean canNegotiateOn(LocalDate today) {
        return canNegotiate && isCurrentOn(today);
    }

    public Document cpf() {
        return Document.parse(document);
    }

    public String maskedDocument() {
        return cpf().masked();
    }

    private void requireActive() {
        Guard.requireState(endedAt == null, "representative.ended", "Representante encerrado não pode ser alterado");
    }

    public UUID getCompanyDebtorId() {
        return companyDebtorId;
    }

    public String getDocumentHash() {
        return documentHash;
    }

    /** Nome (descriptografado). Uso restrito: ficha do devedor e termo do acordo. */
    public String getName() {
        return name;
    }

    public RepresentativeRole getRole() {
        return role;
    }

    public boolean isCanNegotiate() {
        return canNegotiate;
    }

    public LocalDate getValidUntil() {
        return validUntil;
    }

    public String getEvidenceNote() {
        return evidenceNote;
    }

    public Source getSource() {
        return source;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public String getEndReason() {
        return endReason;
    }
}
