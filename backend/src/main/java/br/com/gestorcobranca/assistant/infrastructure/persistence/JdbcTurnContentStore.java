package br.com.gestorcobranca.assistant.infrastructure.persistence;

import br.com.gestorcobranca.assistant.application.port.out.TurnContentStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Descarte do conteúdo da trilha (única alteração aceita pelo gatilho {@code assistant.turn_purge_only}):
 * entrada, contexto e resposta viram nulos e {@code content_purged_at} é preenchido; metadados, hash do
 * contexto, consumo e resultado permanecem. Os argumentos/resultados das ferramentas não têm texto do
 * devedor e ficam.
 */
@Component
class JdbcTurnContentStore implements TurnContentStore {

    private final JdbcTemplate jdbc;

    JdbcTurnContentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int purgeOlderThan(Instant cutoff, int limit) {
        return jdbc.update("""
                update assistant.assistant_turn
                   set input_text_encrypted = null, context_encrypted = null, output_text_encrypted = null,
                       content_purged_at = now()
                 where id in (select id from assistant.assistant_turn
                               where content_purged_at is null and finished_at < ?
                               order by finished_at limit ?)
                """, Timestamp.from(cutoff), limit);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int purgeByDebtor(UUID debtorId) {
        return jdbc.update("""
                update assistant.assistant_turn
                   set input_text_encrypted = null, context_encrypted = null, output_text_encrypted = null,
                       content_purged_at = now()
                 where debtor_id = ? and content_purged_at is null
                """, debtorId);
    }
}
