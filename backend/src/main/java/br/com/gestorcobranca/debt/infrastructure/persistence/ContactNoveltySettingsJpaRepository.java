package br.com.gestorcobranca.debt.infrastructure.persistence;

import br.com.gestorcobranca.debt.domain.model.ContactNoveltySettings;
import br.com.gestorcobranca.debt.domain.repository.ContactNoveltySettingsRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

interface ContactNoveltySettingsJpaRepository extends Repository<ContactNoveltySettings, UUID>,
        ContactNoveltySettingsRepository {

    @Override
    ContactNoveltySettings save(ContactNoveltySettings settings);

    /** A RLS restringe à assessoria corrente; há no máximo uma linha. */
    @Override
    @Query("select s from ContactNoveltySettings s")
    Optional<ContactNoveltySettings> findCurrent();
}
