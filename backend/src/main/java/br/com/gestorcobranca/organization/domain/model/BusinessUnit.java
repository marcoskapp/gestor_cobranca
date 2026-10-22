package br.com.gestorcobranca.organization.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Unidade operacional da assessoria (filial, célula, operação). Equipes pertencem a unidades. */
@Entity
@Table(name = "business_unit", schema = "organization")
public class BusinessUnit extends TenantScopedEntity {

    @Column(name = "code", nullable = false, length = 30)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected BusinessUnit() {
    }

    public BusinessUnit(String code, String name) {
        Guard.require(code != null && !code.isBlank(), "business_unit.code_required", "Código obrigatório");
        Guard.require(name != null && !name.isBlank(), "business_unit.name_required", "Nome obrigatório");
        this.code = code.trim().toUpperCase();
        this.name = name.trim();
        this.active = true;
    }

    public void rename(String name) {
        Guard.require(name != null && !name.isBlank(), "business_unit.name_required", "Nome obrigatório");
        this.name = name.trim();
    }

    public void deactivate() {
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

    public boolean isActive() {
        return active;
    }
}
