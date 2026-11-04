package br.com.gestorcobranca.publicapi.infrastructure.persistence.platform;

import br.com.gestorcobranca.publicapi.domain.model.platform.PlatformBillingPolicyVersion;
import br.com.gestorcobranca.publicapi.domain.repository.platform.PlatformBillingPolicyVersionRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface PlatformBillingPolicyVersionJpaRepository extends JpaRepository<PlatformBillingPolicyVersion, UUID>,
        PlatformBillingPolicyVersionRepository {
}
