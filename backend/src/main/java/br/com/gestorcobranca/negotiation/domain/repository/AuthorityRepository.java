package br.com.gestorcobranca.negotiation.domain.repository;

import br.com.gestorcobranca.negotiation.domain.model.authority.AssignmentSubject;
import br.com.gestorcobranca.negotiation.domain.model.authority.AuthorityAssignment;
import br.com.gestorcobranca.negotiation.domain.model.authority.AuthorityProfile;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Perfis de alçada e atribuições. */
public interface AuthorityRepository {

    AuthorityProfile save(AuthorityProfile profile);

    Optional<AuthorityProfile> findProfile(UUID id);

    Optional<AuthorityProfile> findProfileByCode(String code);

    List<AuthorityProfile> findAllProfiles();

    boolean existsProfileCode(String code);

    AuthorityAssignment save(AuthorityAssignment assignment);

    void delete(AuthorityAssignment assignment);

    Optional<AuthorityAssignment> findAssignment(UUID id);

    /** @param creditorId nulo = atribuição geral (todos os credores) */
    Optional<AuthorityAssignment> findAssignment(AssignmentSubject type, UUID subjectId, UUID creditorId);

    List<AuthorityAssignment> findAssignments(AssignmentSubject type, UUID subjectId);

    List<AuthorityAssignment> findAllAssignments();

    long countAssignmentsOf(UUID profileId);
}
