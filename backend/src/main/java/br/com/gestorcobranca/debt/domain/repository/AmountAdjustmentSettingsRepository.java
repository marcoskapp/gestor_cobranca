package br.com.gestorcobranca.debt.domain.repository;

import br.com.gestorcobranca.debt.domain.model.AmountAdjustmentSettings;

import java.util.Optional;

/** Uma linha por assessoria (RLS). */
public interface AmountAdjustmentSettingsRepository {

    AmountAdjustmentSettings save(AmountAdjustmentSettings settings);

    Optional<AmountAdjustmentSettings> findCurrent();
}
