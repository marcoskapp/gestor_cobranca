package br.com.gestorcobranca.collection.domain.model;

import br.com.gestorcobranca.shared.crypto.EncryptedStringConverter;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Interação registrada num caso (append-only): canal, sentido, tabulação (código e flags
 * vigentes no registro), observação criptografada (texto livre pode conter dado pessoal),
 * duração e autoria. Integrações informam {@code externalRef} (id da chamada/mensagem) para que
 * reenvios não dupliquem o registro.
 */
@Entity
@Table(name = "interaction", schema = "collection")
public class Interaction extends TenantScopedEntity {

    public static final int NOTE_MAX = 2000;

    @Column(name = "case_id", nullable = false, updatable = false)
    private UUID caseId;

    @Column(name = "debtor_id", nullable = false, updatable = false)
    private UUID debtorId;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Column(name = "portfolio_id", nullable = false, updatable = false)
    private UUID portfolioId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false, length = 20, updatable = false)
    private InteractionChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 10, updatable = false)
    private InteractionDirection direction;

    @Column(name = "tabulation_id", nullable = false, updatable = false)
    private UUID tabulationId;

    @Column(name = "outcome_code", nullable = false, length = 40, updatable = false)
    private String outcomeCode;

    @Column(name = "effective_contact", nullable = false, updatable = false)
    private boolean effectiveContact;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "note_encrypted", length = 12000, updatable = false)
    private String note;

    @Column(name = "contact_id", updatable = false)
    private UUID contactId;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "ended_at", updatable = false)
    private Instant endedAt;

    @Column(name = "duration_seconds", updatable = false)
    private Integer durationSeconds;

    @Column(name = "operator_id", updatable = false)
    private UUID operatorId;

    @Column(name = "actor_type", nullable = false, length = 20, updatable = false)
    private String actorType;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20, updatable = false)
    private InteractionSource source;

    @Column(name = "external_ref", length = 120, updatable = false)
    private String externalRef;

    protected Interaction() {
    }

    /**
     * @param operatorId usuário responsável (nulo em registros automáticos)
     * @param actorType  tipo do ator que registrou (USER, SYSTEM, ASSISTANT, INTEGRATION)
     */
    public Interaction(CollectionCase c, InteractionChannel channel, InteractionDirection direction, Tabulation tabulation,
                       String note, UUID contactId, Instant startedAt, Instant endedAt, UUID operatorId,
                       String actorType, InteractionSource source, String externalRef) {
        Guard.require(channel != null, "interaction.channel_required", "Canal obrigatório");
        Guard.require(direction != null, "interaction.direction_required", "Sentido obrigatório");
        Guard.require(tabulation != null, "interaction.tabulation_required", "Tabulação obrigatória");
        Guard.requireState(tabulation.isActive(), "interaction.tabulation_inactive", "Tabulação desativada");
        Guard.require(startedAt != null, "interaction.started_at_required", "Início da interação obrigatório");
        Guard.require(endedAt == null || !endedAt.isBefore(startedAt), "interaction.period_invalid",
                "Fim da interação anterior ao início");
        String cleanNote = note == null || note.isBlank() ? null : note.trim();
        Guard.require(cleanNote == null || cleanNote.length() <= NOTE_MAX, "interaction.note_too_long",
                "Observação com mais de %d caracteres".formatted(NOTE_MAX));
        Guard.require(!tabulation.isRequiresNote() || cleanNote != null, "interaction.note_required",
                "A tabulação %s exige observação".formatted(tabulation.getCode()));
        Guard.require(externalRef == null || externalRef.length() <= 120, "interaction.external_ref_invalid",
                "Referência externa até 120 caracteres");
        this.caseId = c.getId();
        this.debtorId = c.getDebtorId();
        this.creditorId = c.getCreditorId();
        this.portfolioId = c.getPortfolioId();
        this.channel = channel;
        this.direction = direction;
        this.tabulationId = tabulation.getId();
        this.outcomeCode = tabulation.getCode();
        this.effectiveContact = tabulation.isEffectiveContact();
        this.note = cleanNote;
        this.contactId = contactId;
        this.startedAt = startedAt;
        this.endedAt = endedAt;
        this.durationSeconds = endedAt == null ? null : (int) Duration.between(startedAt, endedAt).toSeconds();
        this.operatorId = operatorId;
        this.actorType = actorType;
        this.source = source == null ? InteractionSource.MANUAL : source;
        this.externalRef = externalRef == null || externalRef.isBlank() ? null : externalRef.trim();
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

    public InteractionChannel getChannel() {
        return channel;
    }

    public InteractionDirection getDirection() {
        return direction;
    }

    public UUID getTabulationId() {
        return tabulationId;
    }

    public String getOutcomeCode() {
        return outcomeCode;
    }

    public boolean isEffectiveContact() {
        return effectiveContact;
    }

    public String getNote() {
        return note;
    }

    public UUID getContactId() {
        return contactId;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getEndedAt() {
        return endedAt;
    }

    public Integer getDurationSeconds() {
        return durationSeconds;
    }

    public UUID getOperatorId() {
        return operatorId;
    }

    public String getActorType() {
        return actorType;
    }

    public InteractionSource getSource() {
        return source;
    }

    public String getExternalRef() {
        return externalRef;
    }
}
