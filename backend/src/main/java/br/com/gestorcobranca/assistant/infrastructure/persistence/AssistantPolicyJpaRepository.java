package br.com.gestorcobranca.assistant.infrastructure.persistence;

import br.com.gestorcobranca.assistant.domain.model.AssistantPolicy;
import br.com.gestorcobranca.assistant.domain.repository.AssistantPolicyRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface AssistantPolicyJpaRepository extends JpaRepository<AssistantPolicy, UUID>, AssistantPolicyRepository {
}
