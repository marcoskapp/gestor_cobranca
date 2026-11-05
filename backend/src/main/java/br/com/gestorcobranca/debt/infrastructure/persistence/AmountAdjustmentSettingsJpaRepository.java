package br.com.gestorcobranca.debt.infrastructure.persistence;

import br.com.gestorcobranca.debt.domain.model.AmountAdjustmentSettings;
import br.com.gestorcobranca.debt.domain.repository.AmountAdjustmentSettingsRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

interface AmountAdjustmentSettingsJpaRepository extends Repository<AmountAdjustmentSettings, UUID>,
        AmountAdjustmentSettingsRepository {

    @Override
    AmountAdjustmentSettings save(AmountAdjustmentSettings settings);

    /** A RLS restringe à assessoria corrente; há no máximo uma linha. */
    @Override
    @Query("select s from AmountAdjustmentSettings s")
    Optional<AmountAdjustmentSettings> findCurrent();
}
