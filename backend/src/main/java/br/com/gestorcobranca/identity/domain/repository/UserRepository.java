package br.com.gestorcobranca.identity.domain.repository;

import br.com.gestorcobranca.identity.domain.model.User;
import br.com.gestorcobranca.identity.domain.model.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository {

    User save(User user);

    Optional<User> findById(UUID id);

    /** Leitura com lock pessimista: serializa tentativas de login concorrentes da mesma conta. */
    Optional<User> findByEmailForUpdate(String email);

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    List<User> findAllByIdIn(Collection<UUID> ids);

    long countActiveWithRole(UUID roleId);

    List<User> findActiveByRoleIds(Collection<UUID> roleIds);

    Page<User> search(String text, UserStatus status, UUID roleId, UUID businessUnitId, Pageable pageable);
}
