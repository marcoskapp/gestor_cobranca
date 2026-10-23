package br.com.gestorcobranca.incentive.domain.model.attribution;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.util.Objects;
import java.util.UUID;

/** Participante registrado na formalização do acordo. */
@Embeddable
public class AttributionParticipant {

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 20)
    private ParticipantActorType actorType;

    @Column(name = "actor_id", nullable = false)
    private UUID actorId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private ParticipantRole role;

    protected AttributionParticipant() {
    }

    public AttributionParticipant(ParticipantActorType actorType, UUID actorId, ParticipantRole role) {
        this.actorType = Objects.requireNonNull(actorType, "actorType");
        this.actorId = Objects.requireNonNull(actorId, "actorId");
        this.role = Objects.requireNonNull(role, "role");
    }

    public ParticipantActorType getActorType() {
        return actorType;
    }

    public UUID getActorId() {
        return actorId;
    }

    public ParticipantRole getRole() {
        return role;
    }

    public boolean isHuman() {
        return actorType == ParticipantActorType.USER;
    }
}
