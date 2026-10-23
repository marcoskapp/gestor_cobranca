package br.com.gestorcobranca.legal.domain.repository;

import br.com.gestorcobranca.legal.domain.model.CourtSuspension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CourtSuspensionRepository {

    CourtSuspension save(CourtSuspension suspension);

    Optional<CourtSuspension> findById(UUID id);

    void delete(CourtSuspension suspension);

    /** Suspensões que se sobrepõem ao período [from, to]. */
    List<CourtSuspension> findByStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateAsc(LocalDate to,
                                                                                                    LocalDate from);

    List<CourtSuspension> findAllByOrderByStartDateDesc();
}
