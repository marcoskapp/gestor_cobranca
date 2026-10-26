package br.com.gestorcobranca.telephony.infrastructure.persistence;

import br.com.gestorcobranca.telephony.application.port.out.CallStatistics;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
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

    @Override
    public List<AccountCounters> accountCounters(Instant from, Instant to) {
        return jdbc.query("""
                select provider_account_id,
                       count(*) filter (where dial_started_at is not null),
                       count(*) filter (where dial_started_at is null and status = 'FAILED' and denial_reasons is null),
                       count(*) filter (where answered_at is not null),
                       count(*) filter (where connected_at is not null),
                       count(*) filter (where dial_started_at is not null and result = 'FAILED'),
                       count(*) filter (where fallback_from_account_id is not null and dial_started_at is not null),
                       coalesce(sum(ring_seconds) filter (where dial_started_at is not null and ended_at is not null), 0),
                       count(*) filter (where dial_started_at is not null and ended_at is not null),
                       coalesce(sum(talk_seconds) filter (where connected_at is not null), 0),
                       coalesce(sum(greatest(0, extract(epoch from (ended_at - answered_at))))
                                filter (where answered_at is not null and ended_at is not null), 0)
                  from telephony.phone_call
                 where direction = 'OUTBOUND' and queued_at >= ? and queued_at < ?
                 group by provider_account_id
                """, (rs, i) -> new AccountCounters(rs.getObject(1, UUID.class), rs.getLong(2), rs.getLong(3),
                rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getLong(9), rs.getLong(10),
                Math.round(rs.getDouble(11))), Timestamp.from(from), Timestamp.from(to));
    }
}
