package br.com.gestorcobranca.identity.infrastructure.persistence;

import br.com.gestorcobranca.identity.domain.model.User;
import br.com.gestorcobranca.identity.domain.model.UserStatus;
import br.com.gestorcobranca.identity.domain.repository.UserRepository;
import jakarta.persistence.LockModeType;
import jakarta.persistence.criteria.Join;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface UserJpaRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User>, UserRepository {

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.email = :email")
    Optional<User> findByEmailForUpdate(String email);

    @Override
    Optional<User> findByEmail(String email);

    @Override
    boolean existsByEmail(String email);

    @Override
    List<User> findAllByIdIn(Collection<UUID> ids);

    @Override
    @Query("""
            select count(u) from User u join u.roleIds r
            where r = :roleId and u.status = br.com.gestorcobranca.identity.domain.model.UserStatus.ACTIVE
            """)
    long countActiveWithRole(UUID roleId);

    @Override
    @Query("""
            select distinct u from User u join u.roleIds r
            where r in :roleIds and u.status = br.com.gestorcobranca.identity.domain.model.UserStatus.ACTIVE
            """)
    List<User> findActiveByRoleIds(Collection<UUID> roleIds);

    @Override
    default Page<User> search(String text, UserStatus status, UUID roleId, UUID businessUnitId, Pageable pageable) {
        Specification<User> spec = Specification.unrestricted();
        if (status != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("status"), status));
        }
        if (businessUnitId != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("businessUnitId"), businessUnitId));
        }
        if (roleId != null) {
            spec = spec.and((root, query, cb) -> {
                Join<User, UUID> roles = root.join("roleIds");
                return cb.equal(roles, roleId);
            });
        }
        if (text != null && !text.isBlank()) {
            String like = "%" + text.trim().toLowerCase() + "%";
            spec = spec.and((root, query, cb) -> cb.or(cb.like(cb.lower(root.get("name")), like),
                    cb.like(root.get("email"), like)));
        }
        return findAll(spec, pageable);
    }
}
