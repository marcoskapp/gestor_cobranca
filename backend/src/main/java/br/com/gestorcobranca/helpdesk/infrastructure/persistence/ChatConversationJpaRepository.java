package br.com.gestorcobranca.helpdesk.infrastructure.persistence;

import br.com.gestorcobranca.helpdesk.domain.model.ChatConversation;
import br.com.gestorcobranca.helpdesk.domain.repository.ChatConversationRepository;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface ChatConversationJpaRepository extends JpaRepository<ChatConversation, UUID>, ChatConversationRepository {
}
