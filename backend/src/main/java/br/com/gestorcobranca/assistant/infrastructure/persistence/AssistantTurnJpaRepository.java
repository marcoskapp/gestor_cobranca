package br.com.gestorcobranca.assistant.infrastructure.persistence;

import br.com.gestorcobranca.assistant.domain.model.AssistantTurn;
import br.com.gestorcobranca.assistant.domain.repository.AssistantTurnRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface AssistantTurnJpaRepository extends JpaRepository<AssistantTurn, UUID>, AssistantTurnRepository {
}
