package br.com.gestorcobranca.telephony.infrastructure.persistence;

import br.com.gestorcobranca.telephony.application.port.out.CallStatistics;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Contadores da campanha numa única varredura do índice {@code ix_call_campaign} (tenant corrente,
 * RLS). "Atendida por pessoa" = conectada a operador ou abandonada; secretária (AMD) fica fora do
 * denominador do abandono.
 */
@Component
class JdbcCallStatistics implements CallStatistics {

    private final JdbcTemplate jdbc;

    JdbcCallStatistics(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Counters campaignCounters(UUID campaignId, Instant since) {
        return jdbc.queryForObject("""
                select count(*) filter (where dial_started_at is not null),
                       count(*) filter (where answered_at is not null),
                       count(*) filter (where connected_at is not null or result = 'ABANDONED'),
                       count(*) filter (where connected_at is not null),
                       count(*) filter (where result = 'ABANDONED'),
                       count(*) filter (where result = 'VOICEMAIL'),
                       coalesce(sum(talk_seconds) filter (where connected_at is not null), 0),
                       coalesce(sum(ring_seconds) filter (where dial_started_at is not null and ended_at is not null), 0),
                       count(*) filter (where dial_started_at is not null and ended_at is not null),
                       coalesce(sum(wrap_up_seconds) filter (where tabulated_by is not null), 0),
                       count(*) filter (where tabulated_by is not null)
                  from telephony.phone_call
                 where campaign_id = ? and queued_at >= ?
                """, (rs, i) -> new Counters(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5),
                rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getLong(9), rs.getLong(10), rs.getLong(11)),
                campaignId, Timestamp.from(since));
    }
}
