package br.com.gestorcobranca.negotiation.infrastructure.persistence;

import br.com.gestorcobranca.negotiation.domain.model.authority.AssignmentSubject;
import br.com.gestorcobranca.negotiation.domain.model.authority.AuthorityAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface AuthorityAssignmentJpa extends JpaRepository<AuthorityAssignment, UUID> {

    Optional<AuthorityAssignment> findBySubjectTypeAndSubjectIdAndCreditorId(AssignmentSubject type, UUID subjectId,
                                                                             UUID creditorId);

    Optional<AuthorityAssignment> findBySubjectTypeAndSubjectIdAndCreditorIdIsNull(AssignmentSubject type, UUID subjectId);

    List<AuthorityAssignment> findBySubjectTypeAndSubjectIdOrderByCreditorId(AssignmentSubject type, UUID subjectId);

    List<AuthorityAssignment> findAllByOrderBySubjectTypeAscSubjectIdAscCreditorIdAsc();

    long countByProfileId(UUID profileId);
}
