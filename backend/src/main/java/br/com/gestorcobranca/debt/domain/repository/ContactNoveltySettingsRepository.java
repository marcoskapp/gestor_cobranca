package br.com.gestorcobranca.debt.domain.repository;

import br.com.gestorcobranca.debt.domain.model.ContactNoveltySettings;

import java.util.Optional;

/** Uma linha por assessoria (RLS). */
public interface ContactNoveltySettingsRepository {

    ContactNoveltySettings save(ContactNoveltySettings settings);

    Optional<ContactNoveltySettings> findCurrent();
}
