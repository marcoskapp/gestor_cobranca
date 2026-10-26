package br.com.gestorcobranca.helpdesk.infrastructure.persistence;

import br.com.gestorcobranca.helpdesk.domain.model.ChatConversation;
import br.com.gestorcobranca.helpdesk.domain.repository.ChatConversationRepository;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

interface ChatConversationJpaRepository extends JpaRepository<ChatConversation, UUID>, ChatConversationRepository {

    @Override
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from ChatConversation c where c.id = :id")
    Optional<ChatConversation> findByIdForUpdate(@Param("id") UUID id);
}
