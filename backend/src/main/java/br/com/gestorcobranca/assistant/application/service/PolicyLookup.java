package br.com.gestorcobranca.assistant.application.service;

import br.com.gestorcobranca.assistant.domain.model.AssistantPolicy;
import br.com.gestorcobranca.assistant.domain.model.PolicySettings;
import br.com.gestorcobranca.assistant.domain.model.PolicyStatus;
import br.com.gestorcobranca.assistant.domain.repository.AssistantPolicyRepository;
import br.com.gestorcobranca.assistant.domain.service.PolicyResolver;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolução da política vigente a cada turno (sem cache: desligamento e nova versão valem na próxima
 * mensagem, inclusive nas conversas em andamento).
 */
@Component
class PolicyLookup {

    /** Política publicada com os parâmetros já interpretados. */
    record Effective(AssistantPolicy policy, PolicySettings settings) {
    }

    private final AssistantPolicyRepository policies;
    private final AssistantCodec codec;

    PolicyLookup(AssistantPolicyRepository policies, AssistantCodec codec) {
        this.policies = policies;
        this.codec = codec;
    }

    @Transactional(readOnly = true)
    Optional<Effective> effective(UUID creditorId, UUID portfolioId) {
        return PolicyResolver.resolve(policies.findByStatus(PolicyStatus.PUBLISHED), creditorId, portfolioId)
                .map(p -> new Effective(p, codec.settings(p.getSettings()).normalized()));
    }
}
