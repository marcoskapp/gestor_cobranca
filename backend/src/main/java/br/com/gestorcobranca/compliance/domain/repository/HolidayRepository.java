package br.com.gestorcobranca.compliance.domain.repository;

import br.com.gestorcobranca.compliance.domain.model.Holiday;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface HolidayRepository {

    Holiday save(Holiday holiday);

    Optional<Holiday> findById(UUID id);

    boolean existsByDate(LocalDate date);

    /** Feriados entre as datas (inclusive), em ordem de data. */
    List<Holiday> findByDateBetweenOrderByDateAsc(LocalDate from, LocalDate to);

    void delete(Holiday holiday);
}
