package br.com.gestorcobranca.incentive.infrastructure.persistence;

import br.com.gestorcobranca.incentive.domain.model.plan.GoalTarget;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface GoalTargetJpa extends JpaRepository<GoalTarget, UUID> {

    Optional<GoalTarget> findByPeriodStartAndSubjectTypeAndSubjectId(LocalDate periodStart, GoalTarget.SubjectType type,
                                                                     UUID subjectId);

    List<GoalTarget> findByPeriodStart(LocalDate periodStart);
}
