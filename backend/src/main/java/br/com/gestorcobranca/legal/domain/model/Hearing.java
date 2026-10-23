package br.com.gestorcobranca.legal.domain.model;

import br.com.gestorcobranca.shared.crypto.EncryptedStringConverter;
import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.net.URI;
import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Audiência do processo: tipo, data e hora, forma (presencial/videoconferência), local ou link
 * (somente HTTPS), participantes internos (usuários) e externos (texto criptografado — nomes de
 * prepostos e advogados), situação, resultado e observações (criptografadas).
 */
@Entity
@Table(name = "hearing", schema = "legal")
public class Hearing extends TenantScopedEntity {

    @Column(name = "lawsuit_id", nullable = false, updatable = false)
    private UUID lawsuitId;

    @Column(name = "matter_id", nullable = false, updatable = false)
    private UUID matterId;

    @Column(name = "creditor_id", nullable = false, updatable = false)
    private UUID creditorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private HearingType type;

    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 20)
    private HearingMode mode;

    @Column(name = "location", length = 300)
    private String location;

    @Column(name = "link", length = 500)
    private String link;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "hearing_participant", schema = "legal", joinColumns = @JoinColumn(name = "hearing_id"))
    @Column(name = "user_id", nullable = false)
    private Set<UUID> participantUserIds = new HashSet<>();

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "external_participants_encrypted", length = 8000)
    private String externalParticipants;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private HearingStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", length = 20)
    private HearingOutcome outcome;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "result_notes_encrypted", length = 24000)
    private String resultNotes;

    @Column(name = "reschedule_count", nullable = false)
    private int rescheduleCount;

    protected Hearing() {
    }

    public Hearing(UUID lawsuitId, UUID matterId, UUID creditorId, HearingType type, Instant scheduledAt, HearingMode mode,
                   String location, String link, Collection<UUID> participants, String externalParticipants) {
        this.lawsuitId = lawsuitId;
        this.matterId = matterId;
        this.creditorId = creditorId;
        this.status = HearingStatus.SCHEDULED;
        define(type, scheduledAt, mode, location, link, participants, externalParticipants);
    }

    private void define(HearingType type, Instant at, HearingMode mode, String location, String link,
                        Collection<UUID> participants, String external) {
        Guard.require(type != null, "legal_hearing.type_required", "Tipo da audiência obrigatório");
        Guard.require(at != null, "legal_hearing.date_required", "Data e hora obrigatórias");
        Guard.require(mode != null, "legal_hearing.mode_required", "Forma da audiência obrigatória");
        String place = text(location, 300);
        String url = text(link, 500);
        Guard.require(url == null || isHttps(url), "legal_hearing.link_invalid", "Link deve ser uma URL HTTPS");
        Guard.require(mode == HearingMode.IN_PERSON ? place != null : url != null || place != null,
                "legal_hearing.place_required", "Informe o local (presencial) ou o link (videoconferência)");
        Guard.require(mode != HearingMode.VIDEO || url != null, "legal_hearing.link_required",
                "Audiência por videoconferência exige o link");
        Guard.require(external == null || external.length() <= 2000, "legal_hearing.participants_too_long",
                "Participantes externos até 2000 caracteres");
        this.type = type;
        this.scheduledAt = at;
        this.mode = mode;
        this.location = place;
        this.link = url;
        this.participantUserIds = participants == null ? new HashSet<>() : new HashSet<>(participants);
        this.externalParticipants = external == null || external.isBlank() ? null : external.trim();
    }

    public void reschedule(Instant at, HearingMode mode, String location, String link, Collection<UUID> participants,
                           String externalParticipants) {
        Guard.requireState(status == HearingStatus.SCHEDULED, "legal_hearing.not_scheduled", "Audiência já encerrada");
        boolean moved = at != null && !at.equals(scheduledAt);
        define(type, at, mode, location, link, participants, externalParticipants);
        if (moved) {
            rescheduleCount++;
        }
    }

    public void recordResult(HearingOutcome outcome, String notes, Instant now) {
        Guard.requireState(status == HearingStatus.SCHEDULED, "legal_hearing.not_scheduled", "Audiência já encerrada");
        Guard.require(outcome != null, "legal_hearing.outcome_required", "Resultado obrigatório");
        Guard.requireState(!scheduledAt.isAfter(now), "legal_hearing.not_held_yet",
                "A audiência ainda não aconteceu");
        Guard.require(notes == null || notes.length() <= 6000, "legal_hearing.notes_too_long", "Observações até 6000 caracteres");
        this.status = HearingStatus.HELD;
        this.outcome = outcome;
        this.resultNotes = notes == null || notes.isBlank() ? null : notes.trim();
    }

    public void cancel(String reason) {
        Guard.requireState(status == HearingStatus.SCHEDULED, "legal_hearing.not_scheduled", "Audiência já encerrada");
        Guard.require(reason != null && !reason.isBlank() && reason.length() <= 6000, "legal_hearing.reason_required",
                "Motivo obrigatório");
        this.status = HearingStatus.CANCELLED;
        this.resultNotes = reason.trim();
    }

    private static boolean isHttps(String url) {
        try {
            URI uri = URI.create(url);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String text(String value, int max) {
        String v = value == null || value.isBlank() ? null : value.trim();
        Guard.require(v == null || v.length() <= max, "legal_hearing.text_too_long",
                "Texto deve ter até %d caracteres".formatted(max));
        return v;
    }

    public UUID getLawsuitId() {
        return lawsuitId;
    }

    public UUID getMatterId() {
        return matterId;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public HearingType getType() {
        return type;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public HearingMode getMode() {
        return mode;
    }

    public String getLocation() {
        return location;
    }

    public String getLink() {
        return link;
    }

    public Set<UUID> getParticipantUserIds() {
        return Set.copyOf(participantUserIds);
    }

    public String getExternalParticipants() {
        return externalParticipants;
    }

    public HearingStatus getStatus() {
        return status;
    }

    public HearingOutcome getOutcome() {
        return outcome;
    }

    public String getResultNotes() {
        return resultNotes;
    }

    public int getRescheduleCount() {
        return rescheduleCount;
    }
}
