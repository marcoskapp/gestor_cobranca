package br.com.gestorcobranca.shared.events;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Registro de eventos já processados por consumidor, para consumo idempotente. */
@Component
public class ConsumedEventRegistry {

    private final JdbcTemplate jdbcTemplate;

    public ConsumedEventRegistry(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** @return true se esta é a primeira vez que o consumidor processa o evento. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean markConsumed(String consumer, IntegrationEvent event) {
        int inserted = jdbcTemplate.update("""
                        insert into shared.consumed_event (consumer, event_id, event_type, tenant_id, consumed_at)
                        values (?, ?, ?, ?, now())
                        on conflict (consumer, event_id) do nothing
                        """,
                consumer, event.eventId(), event.getClass().getSimpleName(), event.tenantId());
        return inserted == 1;
    }
}
