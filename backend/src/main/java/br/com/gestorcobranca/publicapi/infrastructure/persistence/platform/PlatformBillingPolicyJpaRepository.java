package br.com.gestorcobranca.publicapi.infrastructure.persistence.platform;

import br.com.gestorcobranca.publicapi.domain.model.platform.PlatformBillingPolicy;
import br.com.gestorcobranca.publicapi.domain.repository.platform.PlatformBillingPolicyRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Uma política por assessoria ({@code unique (tenant_id)}); a assessoria vem do RLS. */
interface PlatformBillingPolicyJpaRepository extends JpaRepository<PlatformBillingPolicy, UUID>,
        PlatformBillingPolicyRepository {

    Optional<PlatformBillingPolicy> findFirstByOrderByCreatedAtAsc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PlatformBillingPolicy p")
    List<PlatformBillingPolicy> lockAll();

    @Override
    default Optional<PlatformBillingPolicy> findCurrent() {
        return findFirstByOrderByCreatedAtAsc();
    }

    @Override
    default Optional<PlatformBillingPolicy> findCurrentForUpdate() {
        return lockAll().stream().findFirst();
    }
}
