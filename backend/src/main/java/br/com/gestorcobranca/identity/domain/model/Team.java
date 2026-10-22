package br.com.gestorcobranca.identity.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Equipe de cobrança: agrupa operadores sob um supervisor para filas e distribuição. */
@Entity
@Table(name = "team", schema = "identity")
public class Team extends TenantScopedEntity {

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "business_unit_id")
    private UUID businessUnitId;

    @Column(name = "supervisor_id")
    private UUID supervisorId;

    @Column(name = "active", nullable = false)
    private boolean active;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "team_member", schema = "identity", joinColumns = @JoinColumn(name = "team_id"))
    @Column(name = "user_id", nullable = false)
    private Set<UUID> memberIds = new HashSet<>();

    protected Team() {
    }

    public Team(String name, UUID businessUnitId, UUID supervisorId) {
        update(name, businessUnitId, supervisorId);
        this.active = true;
    }

    public void update(String name, UUID businessUnitId, UUID supervisorId) {
        Guard.require(name != null && !name.isBlank(), "team.name_required", "Nome da equipe obrigatório");
        this.name = name.trim();
        this.businessUnitId = businessUnitId;
        this.supervisorId = supervisorId;
    }

    public void defineMembers(Collection<UUID> members) {
        this.memberIds.clear();
        if (members != null) {
            this.memberIds.addAll(members);
        }
    }

    public void deactivate() {
        this.active = false;
    }

    public void activate() {
        this.active = true;
    }

    public String getName() {
        return name;
    }

    public UUID getBusinessUnitId() {
        return businessUnitId;
    }

    public UUID getSupervisorId() {
        return supervisorId;
    }

    public boolean isActive() {
        return active;
    }

    public Set<UUID> getMemberIds() {
        return Set.copyOf(memberIds);
    }
}
