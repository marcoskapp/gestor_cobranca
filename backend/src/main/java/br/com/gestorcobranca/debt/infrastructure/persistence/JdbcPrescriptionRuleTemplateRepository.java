package br.com.gestorcobranca.debt.infrastructure.persistence;

import br.com.gestorcobranca.debt.domain.model.InstrumentType;
import br.com.gestorcobranca.debt.domain.model.InterruptionPolicy;
import br.com.gestorcobranca.debt.domain.model.PrescriptionBase;
import br.com.gestorcobranca.debt.domain.model.PrescriptionClaim;
import br.com.gestorcobranca.debt.domain.model.PrescriptionRule;
import br.com.gestorcobranca.debt.domain.repository.PrescriptionRuleTemplateRepository;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/** Leitura da tabela de mercado (dado global, sem RLS). */
@Repository
class JdbcPrescriptionRuleTemplateRepository implements PrescriptionRuleTemplateRepository {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcPrescriptionRuleTemplateRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Template> findAll() {
        return jdbc.query("""
                select instrument_type, claim, term_months, base, start_offset_days, interruption_policy, alert_days,
                       legal_basis
                from debt.prescription_rule_template
                order by instrument_type, claim
                """, Map.of(), (rs, i) -> new Template(InstrumentType.valueOf(rs.getString(1)),
                PrescriptionClaim.valueOf(rs.getString(2)),
                new PrescriptionRule.Terms(rs.getInt(3), PrescriptionBase.valueOf(rs.getString(4)), rs.getInt(5),
                        InterruptionPolicy.valueOf(rs.getString(6)), rs.getInt(7), rs.getString(8))));
    }
}
