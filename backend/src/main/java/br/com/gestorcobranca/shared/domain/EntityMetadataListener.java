package br.com.gestorcobranca.shared.domain;

import br.com.gestorcobranca.shared.security.Actor;
import br.com.gestorcobranca.shared.security.CurrentActor;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Preenche tenant, autor e datas de criação/alteração. Instanciado pelo Hibernate através do
 * container de beans do Spring, por isso recebe dependências por construtor.
 * <p>
 * Uma entidade tenant-scoped persistida sem ator no contexto é um erro de programação e
 * falha imediatamente (antes mesmo da política RLS do banco recusá-la).
 */
public class EntityMetadataListener {

    private final CurrentActor currentActor;
    private final Clock clock;

    public EntityMetadataListener(CurrentActor currentActor, Clock clock) {
        this.currentActor = currentActor;
        this.clock = clock;
    }

    @PrePersist
    public void prePersist(Object entity) {
        if (!(entity instanceof BaseEntity base)) {
            return;
        }
        Actor actor = currentActor.find().orElse(null);
        UUID actorId = actor == null ? null : actor.id();
        base.markCreated(Instant.now(clock), actorId);
        if (entity instanceof TenantScopedEntity scoped) {
            if (actor == null) {
                throw new CurrentActor.MissingExecutionContextException();
            }
            scoped.assignTenant(actor.tenantId());
        }
    }

    @PreUpdate
    public void preUpdate(Object entity) {
        if (entity instanceof BaseEntity base) {
            base.markUpdated(Instant.now(clock), currentActor.find().map(Actor::id).orElse(null));
        }
    }
}
