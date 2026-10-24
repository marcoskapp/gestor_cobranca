package br.com.gestorcobranca.scoring.domain.repository;

import br.com.gestorcobranca.scoring.domain.model.ScoringSettings;

import java.util.Optional;

public interface ScoringSettingsRepository {

    ScoringSettings save(ScoringSettings settings);

    /** Parâmetros da assessoria corrente (RLS), se já criados. */
    Optional<ScoringSettings> findCurrent();
}
