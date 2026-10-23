package br.com.gestorcobranca.negotiation.infrastructure.persistence;

import br.com.gestorcobranca.negotiation.application.port.out.NegotiationPersonalData;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Anonimização das simulações (tabela append-only): o gatilho {@code simulation_anonymize_only}
 * só aceita apagar a observação da justificativa, na coluna e na memória de cálculo.
 */
@Component
class JdbcNegotiationPersonalData implements NegotiationPersonalData {

    private final JdbcTemplate jdbcTemplate;

    JdbcNegotiationPersonalData(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int anonymizeSimulations(UUID debtorId) {
        return jdbcTemplate.update("""
                update negotiation.simulation
                   set justification_note = null,
                       calculation = case when jsonb_typeof(calculation -> 'justification') = 'object'
                                          then jsonb_set(calculation, '{justification,note}', 'null'::jsonb)
                                          else calculation end
                 where debtor_id = ?
                   and (justification_note is not null
                        or jsonb_typeof(calculation -> 'justification' -> 'note') = 'string')
                """, debtorId);
    }
}
