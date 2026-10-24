package br.com.gestorcobranca.portal.domain.repository;

import br.com.gestorcobranca.portal.domain.model.PortalConfiguration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PortalConfigurationRepository {

    PortalConfiguration save(PortalConfiguration configuration);

    /** Grava já sincronizando com o banco (a rota pública referencia a configuração). */
    PortalConfiguration saveAndFlush(PortalConfiguration configuration);

    Optional<PortalConfiguration> findById(UUID id);

    Optional<PortalConfiguration> findByCreditorId(UUID creditorId);

    List<PortalConfiguration> findAllByOrderByDisplayNameAsc();

    boolean existsBySlug(String slug);
}
