package br.com.gestorcobranca.portal.infrastructure.persistence;

import br.com.gestorcobranca.portal.application.port.out.PortalAgreementLedger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** {@code portal.portal_agreement}: append-only, exceto o primeiro pagamento (privilégio de coluna). */
@Component
class JdbcPortalAgreementLedger implements PortalAgreementLedger {

    private final JdbcTemplate jdbc;

    JdbcPortalAgreementLedger(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(Entry e) {
        jdbc.update("""
                insert into portal.portal_agreement (agreement_id, tenant_id, session_id, configuration_id, creditor_id,
                       portfolio_id, debtor_id, proposal_id, installments, total_amount, formalized_at)
                values (?, shared.current_tenant_id(), ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, e.agreementId(), e.sessionId(), e.configurationId(), e.creditorId(), e.portfolioId(), e.debtorId(),
                e.proposalId(), e.installments(), e.totalAmount(), Timestamp.from(e.formalizedAt()));
    }

    @Override
    public Optional<Entry> find(UUID agreementId) {
        return jdbc.query("""
                select agreement_id, session_id, configuration_id, creditor_id, portfolio_id, debtor_id, proposal_id,
                       installments, total_amount, formalized_at, first_paid_at
                  from portal.portal_agreement where agreement_id = ?
                """, (rs, i) -> new Entry(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getObject(5, UUID.class),
                rs.getObject(6, UUID.class), rs.getObject(7, UUID.class), rs.getInt(8), rs.getBigDecimal(9),
                rs.getTimestamp(10).toInstant(), rs.getTimestamp(11) == null ? null : rs.getTimestamp(11).toInstant()),
                agreementId).stream().findFirst();
    }

    @Override
    public boolean markFirstPaid(UUID agreementId, Instant paidAt) {
        return jdbc.update("update portal.portal_agreement set first_paid_at = ? where agreement_id = ? and first_paid_at is null",
                Timestamp.from(paidAt), agreementId) == 1;
    }

    @Override
    public Set<UUID> agreementIdsOf(UUID debtorId, UUID creditorId) {
        return new HashSet<>(jdbc.queryForList(
                "select agreement_id from portal.portal_agreement where debtor_id = ? and creditor_id = ?", UUID.class,
                debtorId, creditorId));
    }
}
