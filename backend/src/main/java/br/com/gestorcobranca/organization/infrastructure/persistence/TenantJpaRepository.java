package br.com.gestorcobranca.organization.infrastructure.persistence;

import br.com.gestorcobranca.organization.domain.model.Tenant;
import br.com.gestorcobranca.organization.domain.repository.TenantRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface TenantJpaRepository extends JpaRepository<Tenant, UUID>, TenantRepository {

    @Override
    Optional<Tenant> findBySlug(String slug);

    @Override
    boolean existsBySlug(String slug);

    @Override
    @Query("select t from Tenant t where t.platform = true")
    Optional<Tenant> findPlatform();

    @Override
    @Query("select t.id from Tenant t where t.status = br.com.gestorcobranca.organization.domain.model.TenantStatus.ACTIVE")
    List<UUID> findActiveIds();
}
