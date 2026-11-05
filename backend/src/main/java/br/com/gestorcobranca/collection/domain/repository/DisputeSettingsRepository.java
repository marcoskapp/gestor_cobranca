package br.com.gestorcobranca.collection.domain.repository;

import br.com.gestorcobranca.collection.domain.model.DisputeSettings;

import java.util.Optional;

public interface DisputeSettingsRepository {

    DisputeSettings save(DisputeSettings settings);

    /** A linha da assessoria da transação (RLS), se gravada. */
    Optional<DisputeSettings> findFirstByOrderByCreatedAtAsc();
}
