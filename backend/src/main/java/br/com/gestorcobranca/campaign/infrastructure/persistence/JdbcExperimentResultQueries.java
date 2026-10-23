package br.com.gestorcobranca.campaign.infrastructure.persistence;

import br.com.gestorcobranca.campaign.application.port.out.ExperimentResultQueries;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Totais por braço e por devedor: unidade de análise = devedor (a atribuição do braço é por devedor);
 * resultados entre a primeira inscrição do devedor no experimento e o fim do horizonte de observação,
 * contados uma vez por referência (um pagamento atribuído a dois casos do devedor conta uma vez).
 */
@Component
class JdbcExperimentResultQueries implements ExperimentResultQueries {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcExperimentResultQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<ArmTotals> totals(UUID experimentId, Instant matureBefore, int observationDays) {
        MapSqlParameterSource p = new MapSqlParameterSource("experimentId", experimentId)
                .addValue("days", observationDays);
        String mature = "";
        if (matureBefore != null) {
            mature = " having min(e.enrolled_at) <= :matureBefore";
            p.addValue("matureBefore", Timestamp.from(matureBefore));
        }
        return jdbc.query("""
                with units as (
                    select e.debtor_id, e.variant_code as arm, bool_or(e.control) as control,
                           min(e.enrolled_at) as enrolled_at
                      from campaign.enrollment e
                     where e.experiment_id = :experimentId
                     group by e.debtor_id, e.variant_code""" + mature + """
                ), refs as (
                    select distinct on (a.debtor_id, a.kind, a.reference_id) a.debtor_id, a.kind, a.amount, a.occurred_at
                      from campaign.attribution a
                     where a.experiment_id = :experimentId
                     order by a.debtor_id, a.kind, a.reference_id, a.occurred_at
                ), facts as (
                    select u.arm, u.control, u.debtor_id,
                           coalesce(bool_or(r.kind = 'EFFECTIVE_CONTACT'), false) as contact,
                           coalesce(bool_or(r.kind = 'REPLY'), false) as replied,
                           coalesce(bool_or(r.kind = 'AGREEMENT'), false) as agreement,
                           coalesce(sum(r.amount) filter (where r.kind in ('PAYMENT', 'PAYMENT_REVERSAL')), 0) as recovered
                      from units u
                      left join refs r on r.debtor_id = u.debtor_id
                                      and r.occurred_at >= u.enrolled_at
                                      and r.occurred_at <= u.enrolled_at + make_interval(days => :days)
                     group by u.arm, u.control, u.debtor_id
                )
                select arm, control, count(*) as debtors,
                       count(*) filter (where contact) as contact,
                       count(*) filter (where replied) as replied,
                       count(*) filter (where agreement) as agreement,
                       count(*) filter (where recovered > 0) as paying,
                       coalesce(sum(recovered), 0) as recovered,
                       coalesce(sum(recovered * recovered), 0) as recovered_sq
                  from facts group by arm, control order by control desc, arm
                """, p, (rs, i) -> new ArmTotals(rs.getString("arm"), rs.getBoolean("control"), rs.getLong("debtors"),
                rs.getLong("contact"), rs.getLong("replied"), rs.getLong("agreement"), rs.getLong("paying"),
                rs.getBigDecimal("recovered"), rs.getBigDecimal("recovered_sq")));
    }
}
