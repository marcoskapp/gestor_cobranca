package br.com.gestorcobranca.negotiation.domain.model.authority;

import br.com.gestorcobranca.shared.domain.Guard;
import br.com.gestorcobranca.shared.domain.TenantScopedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Atribuição de um perfil de alçada a um usuário ou a uma política do assistente, para todos os
 * credores ({@code creditorId} nulo) ou para um credor específico — a atribuição por credor
 * prevalece sobre a geral. Uma atribuição por (sujeito, credor); trocar o perfil altera a linha.
 */
@Entity
@Table(name = "authority_assignment", schema = "negotiation")
public class AuthorityAssignment extends TenantScopedEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, updatable = false, length = 20)
    private AssignmentSubject subjectType;

    @Column(name = "subject_id", nullable = false, updatable = false)
    private UUID subjectId;

    @Column(name = "creditor_id", updatable = false)
    private UUID creditorId;

    @Column(name = "profile_id", nullable = false)
    private UUID profileId;

    protected AuthorityAssignment() {
    }

    public AuthorityAssignment(AssignmentSubject subjectType, UUID subjectId, UUID creditorId, UUID profileId) {
        Guard.require(subjectType != null && subjectId != null, "authority.subject_required", "Sujeito obrigatório");
        Guard.require(profileId != null, "authority.profile_required", "Perfil obrigatório");
        this.subjectType = subjectType;
        this.subjectId = subjectId;
        this.creditorId = creditorId;
        this.profileId = profileId;
    }

    /** @return true se o perfil mudou */
    public boolean changeProfile(UUID profileId) {
        Guard.require(profileId != null, "authority.profile_required", "Perfil obrigatório");
        boolean changed = !profileId.equals(this.profileId);
        this.profileId = profileId;
        return changed;
    }

    public AssignmentSubject getSubjectType() {
        return subjectType;
    }

    public UUID getSubjectId() {
        return subjectId;
    }

    public UUID getCreditorId() {
        return creditorId;
    }

    public UUID getProfileId() {
        return profileId;
    }
}
