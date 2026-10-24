package br.com.gestorcobranca.portal.infrastructure.persistence;

import br.com.gestorcobranca.portal.domain.model.PortalConfiguration;
import br.com.gestorcobranca.portal.domain.repository.PortalConfigurationRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface PortalConfigurationJpaRepository extends JpaRepository<PortalConfiguration, UUID>, PortalConfigurationRepository {
}
