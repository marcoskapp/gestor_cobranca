package br.com.gestorcobranca.debt.domain.model;

import br.com.gestorcobranca.shared.crypto.EncryptedStringConverter;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Contato de um devedor. Agregado próprio (referencia o devedor por id): contatos mudam com
 * frequência e de forma independente do cadastro (validação por provedores, retorno de entrega,
 * marcação por operador), sem disputar o lock otimista do devedor.
 * <p>
 * O valor é imutável — outro telefone é outro contato — e fica criptografado; {@code valueHash}
 * é o blind index que impede duplicidade por (devedor, tipo, valor).
 */
@Entity
@Table(name = "debtor_contact", schema = "debt")
public class DebtorContact extends TenantScopedEntity {

    @Column(name = "debtor_id", nullable = false, updatable = false)
    private UUID debtorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20, updatable = false)
    private ContactType type;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "value_encrypted", nullable = false, length = 2048, updatable = false)
    private String value;

    @Column(name = "value_hash", nullable = false, length = 64, updatable = false)
    private String valueHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "label", nullable = false, length = 20)
    private ContactLabel label;

    @Column(name = "whatsapp", nullable = false)
    private boolean whatsapp;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20, updatable = false)
    private ContactSource source;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ContactStatus status;

    @Column(name = "priority", nullable = false)
    private int priority;

    @Column(name = "last_verified_at")
    private Instant lastVerifiedAt;

    @Column(name = "invalid_reason", length = 500)
    private String invalidReason;

    protected DebtorContact() {
    }

    /**
     * @param valueHash blind index de {@code value.hashKey()} com finalidade {@code value.hashPurpose()}
     */
    public DebtorContact(UUID debtorId, ContactValue value, String valueHash, ContactLabel label, boolean whatsapp,
                         ContactSource source, int priority) {
        Guard.require(debtorId != null, "contact.debtor_required", "Devedor obrigatório");
        Objects.requireNonNull(value, "value");
        Guard.require(valueHash != null && !valueHash.isBlank(), "contact.hash_required", "Índice do contato obrigatório");
        Guard.require(source != null, "contact.source_required", "Origem do contato obrigatória");
        this.debtorId = debtorId;
        this.type = value.type();
        this.value = value.value();
        this.valueHash = valueHash;
        this.source = source;
        this.status = ContactStatus.UNVERIFIED;
        classify(label, whatsapp, priority);
    }

    /** Altera classificação, indicação de WhatsApp e prioridade (menor = usado primeiro). */
    public void classify(ContactLabel label, boolean whatsapp, int priority) {
        Guard.require(!whatsapp || type == ContactType.PHONE, "contact.whatsapp_requires_phone",
                "Somente telefones podem ser marcados como WhatsApp");
        Guard.require(priority >= 0, "contact.priority_invalid", "Prioridade não pode ser negativa");
        this.label = label == null ? ContactLabel.OTHER : label;
        this.whatsapp = whatsapp;
        this.priority = priority;
    }

    public void markValid(Instant now) {
        this.status = ContactStatus.VALID;
        this.invalidReason = null;
        this.lastVerifiedAt = now;
    }

    public void markInvalid(String reason, Instant now) {
        Guard.require(reason != null && !reason.isBlank(), "contact.invalid_reason_required",
                "Informe o motivo da invalidação");
        Guard.require(reason.length() <= 500, "contact.invalid_reason_too_long", "Motivo muito longo");
        this.status = ContactStatus.INVALID;
        this.invalidReason = reason.trim();
        this.lastVerifiedAt = now;
    }

    public ContactValue contactValue() {
        return new ContactValue(type, value);
    }

    public String maskedValue() {
        return contactValue().masked();
    }

    public UUID getDebtorId() {
        return debtorId;
    }

    public ContactType getType() {
        return type;
    }

    /** Valor completo (descriptografado). Uso restrito: revelação auditada ou envio pelo canal. */
    public String getValue() {
        return value;
    }

    public String getValueHash() {
        return valueHash;
    }

    public ContactLabel getLabel() {
        return label;
    }

    public boolean isWhatsapp() {
        return whatsapp;
    }

    public ContactSource getSource() {
        return source;
    }

    public ContactStatus getStatus() {
        return status;
    }

    public int getPriority() {
        return priority;
    }

    public Instant getLastVerifiedAt() {
        return lastVerifiedAt;
    }

    public String getInvalidReason() {
        return invalidReason;
    }
}
