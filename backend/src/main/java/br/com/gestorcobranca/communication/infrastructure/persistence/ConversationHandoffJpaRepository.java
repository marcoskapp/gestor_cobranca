package br.com.gestorcobranca.communication.infrastructure.persistence;

import br.com.gestorcobranca.communication.domain.model.ConversationHandoff;
import br.com.gestorcobranca.communication.domain.repository.ConversationHandoffRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface ConversationHandoffJpaRepository extends JpaRepository<ConversationHandoff, UUID>,
        ConversationHandoffRepository {
}
