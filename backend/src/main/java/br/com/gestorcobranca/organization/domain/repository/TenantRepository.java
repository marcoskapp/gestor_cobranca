package br.com.gestorcobranca.organization.domain.repository;

import br.com.gestorcobranca.organization.domain.model.Tenant;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantRepository {

    Tenant save(Tenant tenant);

    Optional<Tenant> findById(UUID id);

    Optional<Tenant> findBySlug(String slug);

    boolean existsBySlug(String slug);

    Optional<Tenant> findPlatform();

    List<Tenant> findAll();

    List<UUID> findActiveIds();
}
