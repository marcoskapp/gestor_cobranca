package br.com.gestorcobranca.incentive.infrastructure.persistence;

import br.com.gestorcobranca.incentive.domain.model.quality.ConductOccurrence;
import br.com.gestorcobranca.incentive.domain.model.quality.QualityEvaluation;
import br.com.gestorcobranca.incentive.domain.model.quality.QualityForm;
import br.com.gestorcobranca.incentive.domain.repository.QualityRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
class JpaQualityRepository implements QualityRepository {

    private static final Set<UUID> NO_FILTER = Set.of(new UUID(0, 0));

    private final QualityFormJpa forms;
    private final QualityEvaluationJpa evaluations;
    private final ConductOccurrenceJpa occurrences;

    JpaQualityRepository(QualityFormJpa forms, QualityEvaluationJpa evaluations, ConductOccurrenceJpa occurrences) {
        this.forms = forms;
        this.evaluations = evaluations;
        this.occurrences = occurrences;
    }

    @Override
    public QualityForm saveForm(QualityForm form) {
        return forms.save(form);
    }

    @Override
    public Optional<QualityForm> findForm(UUID id) {
        return forms.findById(id);
    }

    @Override
    public List<QualityForm> findForms() {
        return forms.findAllByOrderByActiveDescNameAsc();
    }

    @Override
    public QualityEvaluation saveEvaluation(QualityEvaluation evaluation) {
        return evaluations.save(evaluation);
    }

    @Override
    public List<QualityEvaluation> findEvaluations(UUID userId, LocalDate from, LocalDate to) {
        return evaluations.findByEvaluatedUserIdAndEvaluatedOnBetweenOrderByEvaluatedOnAsc(userId, from, to);
    }

    @Override
    public Page<QualityEvaluation> searchEvaluations(Collection<UUID> userIds, LocalDate from, LocalDate to,
                                                     Pageable pageable) {
        boolean all = userIds == null;
        return evaluations.search(all, all || userIds.isEmpty() ? NO_FILTER : userIds, from, to, pageable);
    }

    @Override
    public ConductOccurrence saveOccurrence(ConductOccurrence occurrence) {
        return occurrences.save(occurrence);
    }

    @Override
    public Optional<ConductOccurrence> findOccurrence(UUID id) {
        return occurrences.findById(id);
    }

    @Override
    public List<ConductOccurrence> findOccurrences(UUID userId, LocalDate from, LocalDate to) {
        return occurrences.findByUserIdAndOccurredOnBetweenOrderByOccurredOnAsc(userId, from, to);
    }

    @Override
    public Page<ConductOccurrence> searchOccurrences(Collection<UUID> userIds, LocalDate from, LocalDate to,
                                                     Pageable pageable) {
        boolean all = userIds == null;
        return occurrences.search(all, all || userIds.isEmpty() ? NO_FILTER : userIds, from, to, pageable);
    }
}
