package br.com.gestorcobranca.legal.infrastructure.persistence;

import br.com.gestorcobranca.legal.domain.model.LegalSettings;
import br.com.gestorcobranca.legal.domain.repository.LegalSettingsRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface LegalSettingsJpaRepository extends JpaRepository<LegalSettings, UUID>, LegalSettingsRepository {
}
