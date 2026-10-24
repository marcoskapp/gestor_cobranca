package br.com.gestorcobranca.assistant.domain.repository;

import br.com.gestorcobranca.assistant.domain.model.AssistantPolicy;
import br.com.gestorcobranca.assistant.domain.model.PolicyStatus;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssistantPolicyRepository {

    AssistantPolicy save(AssistantPolicy policy);

    AssistantPolicy saveAndFlush(AssistantPolicy policy);

    Optional<AssistantPolicy> findById(UUID id);

    List<AssistantPolicy> findAllByIdIn(Collection<UUID> ids);

    List<AssistantPolicy> findByStatus(PolicyStatus status);

    List<AssistantPolicy> findByCodeOrderByPolicyVersionDesc(String code);

    List<AssistantPolicy> findAllByOrderByCodeAscPolicyVersionDesc();
}
