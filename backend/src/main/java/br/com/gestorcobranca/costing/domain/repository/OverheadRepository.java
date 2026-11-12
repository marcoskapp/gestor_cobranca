package br.com.gestorcobranca.costing.domain.repository;

import br.com.gestorcobranca.costing.domain.model.Overhead;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OverheadRepository {

    Overhead save(Overhead overhead);

    Optional<Overhead> findById(UUID id);

    void delete(Overhead overhead);

    /** @param month primeiro dia do mês */
    List<Overhead> findAllByMonth(LocalDate month);

    /** Meses entre {@code from} e {@code to} (primeiros dias), inclusive. */
    List<Overhead> findAllByMonthBetweenOrderByMonthAscCreatedAtAsc(LocalDate from, LocalDate to);

    List<Overhead> findAllByOrderByMonthDescCreatedAtAsc();
}
