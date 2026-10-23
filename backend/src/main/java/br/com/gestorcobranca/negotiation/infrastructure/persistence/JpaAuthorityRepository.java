package br.com.gestorcobranca.negotiation.infrastructure.persistence;

import br.com.gestorcobranca.negotiation.domain.model.authority.AssignmentSubject;
import br.com.gestorcobranca.negotiation.domain.model.authority.AuthorityAssignment;
import br.com.gestorcobranca.negotiation.domain.model.authority.AuthorityProfile;
import br.com.gestorcobranca.negotiation.domain.repository.AuthorityRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaAuthorityRepository implements AuthorityRepository {

    private final AuthorityProfileJpa profiles;
    private final AuthorityAssignmentJpa assignments;

    JpaAuthorityRepository(AuthorityProfileJpa profiles, AuthorityAssignmentJpa assignments) {
        this.profiles = profiles;
        this.assignments = assignments;
    }

    @Override
    public AuthorityProfile save(AuthorityProfile profile) {
        return profiles.save(profile);
    }

    @Override
    public Optional<AuthorityProfile> findProfile(UUID id) {
        return profiles.findById(id);
    }

    @Override
    public Optional<AuthorityProfile> findProfileByCode(String code) {
        return profiles.findByCode(code);
    }

    @Override
    public List<AuthorityProfile> findAllProfiles() {
        return profiles.findAll();
    }

    @Override
    public boolean existsProfileCode(String code) {
        return profiles.existsByCode(code);
    }

    @Override
    public AuthorityAssignment save(AuthorityAssignment assignment) {
        return assignments.save(assignment);
    }

    @Override
    public void delete(AuthorityAssignment assignment) {
        assignments.delete(assignment);
    }

    @Override
    public Optional<AuthorityAssignment> findAssignment(UUID id) {
        return assignments.findById(id);
    }

    @Override
    public Optional<AuthorityAssignment> findAssignment(AssignmentSubject type, UUID subjectId, UUID creditorId) {
        return creditorId == null ? assignments.findBySubjectTypeAndSubjectIdAndCreditorIdIsNull(type, subjectId)
                : assignments.findBySubjectTypeAndSubjectIdAndCreditorId(type, subjectId, creditorId);
    }

    @Override
    public List<AuthorityAssignment> findAssignments(AssignmentSubject type, UUID subjectId) {
        return assignments.findBySubjectTypeAndSubjectIdOrderByCreditorId(type, subjectId);
    }

    @Override
    public List<AuthorityAssignment> findAllAssignments() {
        return assignments.findAllByOrderBySubjectTypeAscSubjectIdAscCreditorIdAsc();
    }

    @Override
    public long countAssignmentsOf(UUID profileId) {
        return assignments.countByProfileId(profileId);
    }
}
