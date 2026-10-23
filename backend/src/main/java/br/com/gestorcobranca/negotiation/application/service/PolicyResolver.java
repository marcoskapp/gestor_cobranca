package br.com.gestorcobranca.negotiation.application.service;

import br.com.gestorcobranca.negotiation.domain.model.policy.CollectionPolicy;
import br.com.gestorcobranca.negotiation.domain.model.policy.PolicyTerms;
import br.com.gestorcobranca.negotiation.domain.repository.CollectionPolicyRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolve a versão de política vigente de um credor numa data: entre as versões publicadas ou
 * aposentadas cuja vigência alcança a data, a de maior número de versão.
 */
@Component
class PolicyResolver {

    private final CollectionPolicyRepository policies;
    private final PolicyTermsCodec codec;

    PolicyResolver(CollectionPolicyRepository policies, PolicyTermsCodec codec) {
        this.policies = policies;
        this.codec = codec;
    }

    record ResolvedPolicy(CollectionPolicy policy, PolicyTerms terms) {
        UUID id() {
            return policy.getId();
        }
    }

    Optional<ResolvedPolicy> effective(UUID creditorId, LocalDate date) {
        return policies.findEffectiveCandidates(creditorId).stream().filter(p -> p.isEffectiveOn(date))
                .max(Comparator.comparingInt(CollectionPolicy::getPolicyVersion))
                .map(p -> new ResolvedPolicy(p, codec.decode(p)));
    }
}
