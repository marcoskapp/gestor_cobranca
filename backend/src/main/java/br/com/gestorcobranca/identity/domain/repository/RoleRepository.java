package br.com.gestorcobranca.identity.domain.repository;

import br.com.gestorcobranca.identity.domain.model.Role;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoleRepository {

    Role save(Role role);

    Optional<Role> findById(UUID id);

    Optional<Role> findByCode(String code);

    boolean existsByCode(String code);

    List<Role> findAll();

    List<Role> findAllByIdIn(Collection<UUID> ids);
}
