package br.com.gestorcobranca.collection.domain.repository;

import br.com.gestorcobranca.collection.domain.model.CollectionSettings;

import java.util.Optional;

/** Parâmetros da assessoria corrente (no máximo um registro por tenant; RLS restringe ao tenant). */
public interface CollectionSettingsRepository {

    CollectionSettings save(CollectionSettings settings);

    Optional<CollectionSettings> findFirstByOrderByCreatedAtAsc();
}
