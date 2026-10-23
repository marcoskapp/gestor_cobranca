package br.com.gestorcobranca.debt.domain.repository;

import br.com.gestorcobranca.debt.domain.model.TitlePrescription;

import java.time.LocalDate;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface TitlePrescriptionRepository {

    TitlePrescription save(TitlePrescription prescription);

    Optional<TitlePrescription> findByTitleId(UUID titleId);

    /**
     * Prescrições de títulos em cobrança (abertos ou suspensos) com data estimada entre
     * {@code from} e {@code to} e ainda não alertadas para essa data, por data estimada.
     */
    List<TitlePrescription> findAlertCandidates(LocalDate from, LocalDate to, int limit);
}
