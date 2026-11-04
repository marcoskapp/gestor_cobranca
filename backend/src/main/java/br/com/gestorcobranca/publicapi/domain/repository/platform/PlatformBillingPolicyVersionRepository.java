package br.com.gestorcobranca.publicapi.domain.repository.platform;

import br.com.gestorcobranca.publicapi.domain.model.platform.PlatformBillingPolicyVersion;

import java.util.List;
import java.util.UUID;

public interface PlatformBillingPolicyVersionRepository {

    PlatformBillingPolicyVersion save(PlatformBillingPolicyVersion version);

    /** Versões da política em ordem crescente. */
    List<PlatformBillingPolicyVersion> findByPolicyIdOrderByPolicyVersionAsc(UUID policyId);
}
