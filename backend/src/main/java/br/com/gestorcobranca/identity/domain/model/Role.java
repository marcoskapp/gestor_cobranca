package br.com.gestorcobranca.identity.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import br.com.gestorcobranca.shared.security.Permission;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

/**
 * Perfil: conjunto nomeado de permissões configurável por assessoria. Perfis de sistema
 * (criados no provisionamento) podem ter permissões ajustadas, mas não podem ser removidos nem
 * renomeados, garantindo que sempre exista um perfil administrador.
 */
@Entity
@Table(name = "role", schema = "identity")
public class Role extends TenantScopedEntity {

    @Column(name = "code", nullable = false, length = 40)
    private String code;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "system_role", nullable = false)
    private boolean systemRole;

    @Column(name = "active", nullable = false)
    private boolean active;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "role_permission", schema = "identity", joinColumns = @JoinColumn(name = "role_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "permission", nullable = false, length = 60)
    private Set<Permission> permissions = new HashSet<>();

    /**
     * Permissões padrão (de {@code DefaultRoles}) já aplicadas a este perfil de sistema. Uma permissão
     * nova da definição padrão é acrescentada uma única vez; se o administrador a retirar depois, ela
     * não volta na próxima sincronização.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "role_default_baseline", schema = "identity", joinColumns = @JoinColumn(name = "role_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "permission", nullable = false, length = 60)
    private Set<Permission> appliedDefaults = new HashSet<>();

    protected Role() {
    }

    public Role(String code, String name, String description, Collection<Permission> permissions, boolean systemRole) {
        Guard.require(code != null && code.matches("[A-Z][A-Z0-9_]{1,39}"), "role.code_invalid",
                "Código do perfil: letras maiúsculas, números e _ (2 a 40)");
        this.code = code;
        this.systemRole = systemRole;
        this.active = true;
        update(name, description, permissions);
        if (systemRole) {
            this.appliedDefaults.addAll(permissions);
        }
    }

    /**
     * Acrescenta a este perfil de sistema as permissões da definição padrão que ainda não foram
     * aplicadas (ex.: permissões criadas por versões novas do sistema). Nunca remove permissões.
     *
     * @return as permissões efetivamente acrescentadas
     */
    public Set<Permission> applyDefaultPermissions(Set<Permission> definition) {
        Guard.requireState(systemRole, "role.not_system", "Somente perfis de sistema recebem permissões padrão");
        Set<Permission> pending = EnumSet.noneOf(Permission.class);
        for (Permission permission : definition) {
            if (permission != Permission.TENANT_MANAGE || (systemRole && "PLATFORM_ADMIN".equals(code))) {
                if (!appliedDefaults.contains(permission)) {
                    pending.add(permission);
                }
            }
        }
        appliedDefaults.addAll(pending);
        Set<Permission> added = EnumSet.noneOf(Permission.class);
        for (Permission permission : pending) {
            if (permissions.add(permission)) {
                added.add(permission);
            }
        }
        return added;
    }

    public void update(String name, String description, Collection<Permission> permissions) {
        Guard.require(name != null && !name.isBlank(), "role.name_required", "Nome do perfil obrigatório");
        Guard.require(permissions != null && !permissions.isEmpty(), "role.permissions_required",
                "Perfil deve ter ao menos uma permissão");
        Guard.require(!permissions.contains(Permission.TENANT_MANAGE) || (systemRole && "PLATFORM_ADMIN".equals(code)),
                "role.platform_permission", "Permissão de plataforma não pode ser atribuída a perfis de assessoria");
        this.name = name.trim();
        this.description = description;
        this.permissions.clear();
        this.permissions.addAll(permissions);
    }

    public void deactivate() {
        Guard.requireState(!systemRole, "role.system_immutable", "Perfis de sistema não podem ser desativados");
        this.active = false;
    }

    public void activate() {
        this.active = true;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public boolean isSystemRole() {
        return systemRole;
    }

    public boolean isActive() {
        return active;
    }

    public Set<Permission> getPermissions() {
        return permissions.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(permissions));
    }
}
