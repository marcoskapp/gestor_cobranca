package br.com.gestorcobranca.compliance.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/**
 * Tentativa de contato com o devedor (append-only). É a base dos limites diário e semanal e do
 * intervalo mínimo; só é gravada pelo porteiro de contato, depois de autorizada.
 */
@Entity
@Immutable
@Table(name = "contact_attempt", schema = "compliance")
public class ContactAttempt extends TenantScopedEntity {

    @Column(name = "debtor_id", nullable = false, updatable = false)
    private UUID debtorId;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20, updatable = false)
    private ContactChannel channel;

    @Column(name = "attempted_at", nullable = false, updatable = false)
    private Instant attemptedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "origin", nullable = false, length = 20, updatable = false)
    private AttemptOrigin origin;

    /** Agregado que originou a tentativa (ex.: {@code message}, {@code call}, {@code campaign_step}). */
    @Column(name = "reference_type", length = 40, updatable = false)
    private String referenceType;

    @Column(name = "reference_id", updatable = false)
    private UUID referenceId;

    /** Cobrança (conta para os limites) ou transacional (resposta devida ao devedor; não conta). */
    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 20, updatable = false)
    private ContactCategory category;

    protected ContactAttempt() {
    }

    public ContactAttempt(UUID debtorId, UUID creditorId, ContactChannel channel, Instant attemptedAt,
                          AttemptOrigin origin, String referenceType, UUID referenceId) {
        this(debtorId, creditorId, channel, attemptedAt, origin, referenceType, referenceId, ContactCategory.COLLECTION);
    }

    public ContactAttempt(UUID debtorId, UUID creditorId, ContactChannel channel, Instant attemptedAt,
                          AttemptOrigin origin, String referenceType, UUID referenceId, ContactCategory category) {
        Guard.require(debtorId != null, "attempt.debtor_required", "Devedor obrigatório");
        Guard.require(creditorId != null, "attempt.creditor_required", "Credor obrigatório");
        Guard.require(channel != null, "attempt.channel_required", "Canal obrigatório");
        Guard.require(attemptedAt != null, "attempt.time_required", "Instante da tentativa obrigatório");
        Guard.require(origin != null, "attempt.origin_required", "Origem da tentativa obrigatória");
        Guard.require((referenceType == null || referenceType.isBlank()) == (referenceId == null),
                "attempt.reference_invalid", "Informe tipo e id da referência juntos");
        Guard.require(referenceType == null || referenceType.length() <= 40, "attempt.reference_invalid",
                "Tipo da referência deve ter até 40 caracteres");
        this.debtorId = debtorId;
        this.creditorId = creditorId;
        this.channel = channel;
        this.attemptedAt = attemptedAt;
        this.origin = origin;
        this.referenceType = referenceId == null ? null : referenceType.trim();
        this.referenceId = referenceId;
        this.category = category == null ? ContactCategory.COLLECTION : category;
    }

    public UUID getDebtorId() {
        return debtorId;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public ContactChannel getChannel() {
        return channel;
    }

    public Instant getAttemptedAt() {
        return attemptedAt;
    }

    public AttemptOrigin getOrigin() {
        return origin;
    }

    public String getReferenceType() {
        return referenceType;
    }

    public UUID getReferenceId() {
        return referenceId;
    }

    public ContactCategory getCategory() {
        return category;
    }
}
