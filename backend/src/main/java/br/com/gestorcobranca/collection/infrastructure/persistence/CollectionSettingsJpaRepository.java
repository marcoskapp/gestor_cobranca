package br.com.gestorcobranca.collection.infrastructure.persistence;

import br.com.gestorcobranca.collection.domain.model.CollectionSettings;
import br.com.gestorcobranca.collection.domain.repository.CollectionSettingsRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface CollectionSettingsJpaRepository extends JpaRepository<CollectionSettings, UUID>, CollectionSettingsRepository {
}
