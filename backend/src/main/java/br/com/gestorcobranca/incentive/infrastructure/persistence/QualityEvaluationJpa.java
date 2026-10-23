package br.com.gestorcobranca.incentive.infrastructure.persistence;

import br.com.gestorcobranca.incentive.domain.model.quality.QualityEvaluation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

interface QualityEvaluationJpa extends JpaRepository<QualityEvaluation, UUID> {

    List<QualityEvaluation> findByEvaluatedUserIdAndEvaluatedOnBetweenOrderByEvaluatedOnAsc(UUID userId, LocalDate from,
                                                                                            LocalDate to);

    @Query("""
            select e from QualityEvaluation e
            where (:allUsers = true or e.evaluatedUserId in :userIds) and e.evaluatedOn between :from and :to
            order by e.evaluatedOn desc, e.createdAt desc
            """)
    Page<QualityEvaluation> search(boolean allUsers, Collection<UUID> userIds, LocalDate from, LocalDate to,
                                   Pageable pageable);
}
