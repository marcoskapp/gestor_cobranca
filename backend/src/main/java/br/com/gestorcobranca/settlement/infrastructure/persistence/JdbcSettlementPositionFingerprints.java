package br.com.gestorcobranca.settlement.infrastructure.persistence;

import br.com.gestorcobranca.settlement.application.port.out.SettlementPositionFingerprints;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Uma instrução com agregados indexados (índices parciais de itens em aberto e por credor), dentro do tenant (RLS).
 * A apuração de um recebimento atualiza o próprio recebimento ({@code updated_at}), então lançamentos novos mudam a
 * marca sem varrer o razão.
 */
@Component
class JdbcSettlementPositionFingerprints implements SettlementPositionFingerprints {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcSettlementPositionFingerprints(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String of(UUID creditorId) {
        return jdbc.queryForObject("""
                select concat_ws('|',
                  (select count(*) || ':' || coalesce(max(updated_at)::text, '') from settlement.receipt
                    where creditor_id = :c and statement_id is null),
                  (select count(*) || ':' || coalesce(max(updated_at)::text, '') from settlement.reimbursable_expense
                    where creditor_id = :c and statement_id is null),
                  (select count(*) || ':' || coalesce(max(updated_at)::text, '') from settlement.statement
                    where creditor_id = :c),
                  (select count(*) || ':' || coalesce(max(a.updated_at)::text, '') from settlement.statement_adjustment a
                    join settlement.statement s on s.id = a.statement_id where s.creditor_id = :c),
                  (select count(*) || ':' || coalesce(max(updated_at)::text, '') from settlement.creditor_transfer
                    where creditor_id = :c),
                  (select count(*) || ':' || coalesce(max(updated_at)::text, '') from settlement.agreement_fact
                    where creditor_id = :c))
                """, new MapSqlParameterSource("c", creditorId), String.class);
    }
}
