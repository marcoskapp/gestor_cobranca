package br.com.gestorcobranca.identity.infrastructure.persistence;

import br.com.gestorcobranca.identity.domain.model.Role;
import br.com.gestorcobranca.identity.domain.repository.RoleRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface RoleJpaRepository extends JpaRepository<Role, UUID>, RoleRepository {

    @Override
    Optional<Role> findByCode(String code);

    @Override
    boolean existsByCode(String code);

    @Override
    List<Role> findAllByIdIn(Collection<UUID> ids);
}
