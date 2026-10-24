package br.com.gestorcobranca.portal.infrastructure.persistence;

import br.com.gestorcobranca.portal.application.port.out.PortalFunnelReader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * Funil por portal e período (sessões abertas no período): uma consulta agregada sob RLS. "Pago" conta
 * os acordos fechados nas sessões do período com primeiro pagamento confirmado.
 */
@Component
class JdbcPortalFunnelReader implements PortalFunnelReader {

    private final JdbcTemplate jdbc;

    JdbcPortalFunnelReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Counts counts(UUID configurationId, Instant from, Instant until) {
        return jdbc.queryForObject("""
                select count(*),
                       count(*) filter (where s.simulated_at is not null),
                       count(*) filter (where s.accepted_at is not null),
                       count(*) filter (where s.pix_issued_at is not null),
                       (select count(*) from portal.portal_agreement a join portal.portal_session x on x.id = a.session_id
                         where a.configuration_id = ? and x.created_at >= ? and x.created_at < ?
                           and a.first_paid_at is not null),
                       (select count(*) from portal.service_request r
                         where r.configuration_id = ? and r.created_at >= ? and r.created_at < ?)
                  from portal.portal_session s
                 where s.configuration_id = ? and s.created_at >= ? and s.created_at < ?
                """, (rs, i) -> new Counts(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5),
                rs.getLong(6)), configurationId, Timestamp.from(from), Timestamp.from(until), configurationId,
                Timestamp.from(from), Timestamp.from(until), configurationId, Timestamp.from(from), Timestamp.from(until));
    }
}
