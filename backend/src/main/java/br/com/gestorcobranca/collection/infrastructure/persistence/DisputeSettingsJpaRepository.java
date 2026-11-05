package br.com.gestorcobranca.collection.infrastructure.persistence;

import br.com.gestorcobranca.collection.domain.model.DisputeSettings;
import br.com.gestorcobranca.collection.domain.repository.DisputeSettingsRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface DisputeSettingsJpaRepository extends JpaRepository<DisputeSettings, UUID>, DisputeSettingsRepository {
}
