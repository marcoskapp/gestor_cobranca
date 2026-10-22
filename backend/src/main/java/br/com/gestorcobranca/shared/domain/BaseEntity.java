package br.com.gestorcobranca.shared.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * Superclasse de todas as entidades persistentes.
 * <ul>
 *   <li>Id UUID v7 atribuído na criação do objeto (não depende do banco).</li>
 *   <li>{@code version}: lock otimista; nulo enquanto a entidade não foi persistida.</li>
 *   <li>Metadados de criação/alteração preenchidos por {@link EntityMetadataListener}.</li>
 * </ul>
 * Igualdade é por identidade ({@code id}).
 */
@MappedSuperclass
@EntityListeners(EntityMetadataListener.class)
public abstract class BaseEntity {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    protected BaseEntity() {
        this.id = Ids.newId();
    }

    public UUID getId() {
        return id;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }

    public boolean isNew() {
        return version == null;
    }

    void markCreated(Instant now, UUID actorId) {
        this.createdAt = now;
        this.createdBy = actorId;
        this.updatedAt = now;
        this.updatedBy = actorId;
    }

    void markUpdated(Instant now, UUID actorId) {
        this.updatedAt = now;
        this.updatedBy = actorId;
    }

    @Override
    public final boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || org.hibernate.Hibernate.getClass(this) != org.hibernate.Hibernate.getClass(o)) {
            return false;
        }
        return id != null && id.equals(((BaseEntity) o).getId());
    }

    @Override
    public final int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + id + "]";
    }
}
