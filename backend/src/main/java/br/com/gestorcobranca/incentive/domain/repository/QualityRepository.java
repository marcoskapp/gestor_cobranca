package br.com.gestorcobranca.incentive.domain.repository;

import br.com.gestorcobranca.incentive.domain.model.quality.ConductOccurrence;
import br.com.gestorcobranca.incentive.domain.model.quality.QualityEvaluation;
import br.com.gestorcobranca.incentive.domain.model.quality.QualityForm;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Formulários, avaliações de monitoria e ocorrências de conduta. */
public interface QualityRepository {

    QualityForm saveForm(QualityForm form);

    Optional<QualityForm> findForm(UUID id);

    List<QualityForm> findForms();

    QualityEvaluation saveEvaluation(QualityEvaluation evaluation);

    List<QualityEvaluation> findEvaluations(UUID userId, LocalDate from, LocalDate to);

    /** @param userIds null = todos */
    Page<QualityEvaluation> searchEvaluations(Collection<UUID> userIds, LocalDate from, LocalDate to, Pageable pageable);

    ConductOccurrence saveOccurrence(ConductOccurrence occurrence);

    Optional<ConductOccurrence> findOccurrence(UUID id);

    List<ConductOccurrence> findOccurrences(UUID userId, LocalDate from, LocalDate to);

    /** @param userIds null = todos */
    Page<ConductOccurrence> searchOccurrences(Collection<UUID> userIds, LocalDate from, LocalDate to, Pageable pageable);
}
