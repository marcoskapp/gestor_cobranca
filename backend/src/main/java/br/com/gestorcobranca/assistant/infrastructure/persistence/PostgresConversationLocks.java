package br.com.gestorcobranca.assistant.infrastructure.persistence;

import br.com.gestorcobranca.assistant.application.port.out.ConversationLocks;
import br.com.gestorcobranca.shared.security.CurrentActor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** {@link ConversationLocks} com {@code pg_advisory_xact_lock} (chave por tenant e conversa). */
@Component
class PostgresConversationLocks implements ConversationLocks {

    private final JdbcTemplate jdbcTemplate;
    private final CurrentActor currentActor;

    PostgresConversationLocks(JdbcTemplate jdbcTemplate, CurrentActor currentActor) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentActor = currentActor;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockConversation(UUID conversationId) {
        jdbcTemplate.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> null,
                "assistant.conversation:" + currentActor.tenantId() + ":" + conversationId);
    }
}
