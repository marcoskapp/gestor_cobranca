package br.com.gestorcobranca.campaign.domain.model;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Segmento salvo: filtros combináveis ({@link SegmentCriteria}, JSON) sobre o read model de
 * audiência, de um credor ou da assessoria ({@code creditorId} nulo — exige acesso a todos os
 * credores). Campanhas referenciam o segmento e somam a ele o próprio credor e a carteira.
 */
@Entity
@Table(name = "segment", schema = "campaign")
public class Segment extends TenantScopedEntity {

    public static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,59}");

    @Column(name = "creditor_id", updatable = false)
    private UUID creditorId;

    @Column(name = "code", nullable = false, length = 60, updatable = false)
    private String code;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "description", length = 500)
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "criteria", nullable = false)
    private String criteria;

    @Column(name = "active", nullable = false)
    private boolean active;

    protected Segment() {
    }

    public Segment(UUID creditorId, String code, String name, String description, String criteriaJson) {
        Guard.require(code != null && CODE.matcher(code).matches(), "segment.code_invalid",
                "Código: letras maiúsculas, dígitos e _ (2 a 60)");
        this.creditorId = creditorId;
        this.code = code;
        this.active = true;
        update(name, description, criteriaJson);
    }

    public void update(String name, String description, String criteriaJson) {
        Guard.require(name != null && !name.isBlank() && name.length() <= 120, "segment.name_invalid",
                "Nome obrigatório (até 120)");
        Guard.require(description == null || description.length() <= 500, "segment.description_invalid",
                "Descrição com até 500 caracteres");
        Guard.require(criteriaJson != null, "segment.criteria_required", "Informe os filtros");
        this.name = name.trim();
        this.description = description;
        this.criteria = criteriaJson;
    }

    public void changeActive(boolean active) {
        this.active = active;
    }

    public UUID getCreditorId() {
        return creditorId;
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

    public String getCriteria() {
        return criteria;
    }

    public boolean isActive() {
        return active;
    }
}
