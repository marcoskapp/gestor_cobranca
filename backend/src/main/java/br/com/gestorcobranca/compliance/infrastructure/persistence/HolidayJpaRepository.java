package br.com.gestorcobranca.compliance.infrastructure.persistence;

import br.com.gestorcobranca.compliance.domain.model.Holiday;
import br.com.gestorcobranca.compliance.domain.repository.HolidayRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

interface HolidayJpaRepository extends JpaRepository<Holiday, UUID>, HolidayRepository {

    @Override
    boolean existsByDate(LocalDate date);

    @Override
    List<Holiday> findByDateBetweenOrderByDateAsc(LocalDate from, LocalDate to);
}
