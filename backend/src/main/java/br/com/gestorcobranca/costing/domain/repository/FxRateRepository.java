package br.com.gestorcobranca.costing.domain.repository;

import br.com.gestorcobranca.costing.domain.model.FxRate;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface FxRateRepository {

    FxRate save(FxRate rate);

    /** @param month primeiro dia do mês */
    Optional<FxRate> findByMonth(LocalDate month);

    List<FxRate> findAllByOrderByMonthAsc();
}
