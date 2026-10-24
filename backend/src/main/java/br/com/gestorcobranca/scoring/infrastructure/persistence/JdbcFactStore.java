package br.com.gestorcobranca.scoring.infrastructure.persistence;

import br.com.gestorcobranca.scoring.application.port.out.FactStore;
import br.com.gestorcobranca.scoring.domain.model.CaseFact;
import br.com.gestorcobranca.scoring.domain.model.FactKind;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** {@link FactStore} em SQL; RLS restringe ao tenant da transação. */
@Repository
@Transactional(propagation = Propagation.MANDATORY)
class JdbcFactStore implements FactStore {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcFactStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean record(UUID sourceEventId, CaseFact f) {
        return jdbc.update("""
                insert into scoring.case_fact (tenant_id, source_event_id, kind, status, case_id, debtor_id, creditor_id,
                    portfolio_id, reference_id, channel, amount, occurred_at, effective_at)
                values (shared.current_tenant_id(), :event, :kind, :status, :caseId, :debtorId, :creditorId,
                        :portfolioId, :referenceId, :channel, :amount, :occurredAt, :effectiveAt)
                on conflict do nothing
                """, new MapSqlParameterSource().addValue("event", sourceEventId)
                .addValue("kind", f.kind().name()).addValue("status", f.status(), Types.VARCHAR)
                .addValue("caseId", f.caseId(), Types.OTHER).addValue("debtorId", f.debtorId(), Types.OTHER)
                .addValue("creditorId", f.creditorId(), Types.OTHER).addValue("portfolioId", f.portfolioId(), Types.OTHER)
                .addValue("referenceId", f.referenceId(), Types.OTHER).addValue("channel", f.channel(), Types.VARCHAR)
                .addValue("amount", f.amount(), Types.NUMERIC)
                .addValue("occurredAt", Timestamp.from(f.occurredAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .addValue("effectiveAt", Timestamp.from(f.effectiveAt()), Types.TIMESTAMP_WITH_TIMEZONE)) > 0;
    }

    @Override
    public List<CaseFact> factsOfDebtors(Collection<UUID> debtorIds, Instant since) {
        if (debtorIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query("""
                select kind, status, case_id, debtor_id, creditor_id, portfolio_id, reference_id, channel, amount,
                       occurred_at, effective_at
                  from scoring.case_fact
                 where debtor_id in (:debtors) and occurred_at >= :since
                """, new MapSqlParameterSource("debtors", debtorIds)
                        .addValue("since", Timestamp.from(since), Types.TIMESTAMP_WITH_TIMEZONE),
                (rs, i) -> new CaseFact(FactKind.valueOf(rs.getString("kind")), rs.getString("status"),
                        rs.getObject("case_id", UUID.class), rs.getObject("debtor_id", UUID.class),
                        rs.getObject("creditor_id", UUID.class), rs.getObject("portfolio_id", UUID.class),
                        rs.getObject("reference_id", UUID.class), rs.getString("channel"), rs.getBigDecimal("amount"),
                        rs.getTimestamp("occurred_at").toInstant(), rs.getTimestamp("effective_at").toInstant()));
    }

    @Override
    public int purgeOlderThan(Instant before, int limit) {
        return jdbc.update("""
                delete from scoring.case_fact f
                 where (f.tenant_id, f.source_event_id, f.kind) in (
                       select tenant_id, source_event_id, kind from scoring.case_fact
                        where occurred_at < :before limit :limit)
                """, new MapSqlParameterSource("before", Timestamp.from(before)).addValue("limit", limit));
    }

    @Override
    public int deleteByDebtor(UUID debtorId) {
        return jdbc.update("delete from scoring.case_fact where debtor_id = :debtor",
                new MapSqlParameterSource("debtor", debtorId));
    }
}
