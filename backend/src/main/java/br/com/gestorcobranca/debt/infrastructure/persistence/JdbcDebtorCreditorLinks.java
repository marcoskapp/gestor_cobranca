package br.com.gestorcobranca.debt.infrastructure.persistence;

import br.com.gestorcobranca.debt.application.port.out.DebtorCreditorLinks;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Índices {@code ix_debt_title_debtor} e {@code ix_obligation_party_debtor_active}; a RLS limita ao tenant corrente. */
@Component
class JdbcDebtorCreditorLinks implements DebtorCreditorLinks {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcDebtorCreditorLinks(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean linked(UUID creditorId, UUID debtorId) {
        if (creditorId == null || debtorId == null) {
            return false;
        }
        Boolean found = jdbc.queryForObject("""
                select exists (select 1 from debt.debt_title t where t.debtor_id = :debtor and t.creditor_id = :creditor)
                    or exists (select 1 from debt.obligation_party op
                                where op.debtor_id = :debtor and op.creditor_id = :creditor and op.ended_on is null)
                """, new MapSqlParameterSource("debtor", debtorId).addValue("creditor", creditorId), Boolean.class);
        return Boolean.TRUE.equals(found);
    }
}
