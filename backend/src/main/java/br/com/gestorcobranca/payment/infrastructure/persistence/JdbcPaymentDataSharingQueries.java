package br.com.gestorcobranca.payment.infrastructure.persistence;

import br.com.gestorcobranca.payment.application.port.out.PaymentDataSharingQueries;
import br.com.gestorcobranca.shared.privacy.DataSharingRows;
import br.com.gestorcobranca.shared.privacy.DataSharingUsage;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Índice: {@code ix_instruction_debtor (tenant_id, debtor_id, created_at desc)}; conta Pix pela chave primária. Conta só
 * a cobrança que chegou ao PSP: registrada ({@code registered_at}), paga ou recusada por ele ({@code FAILED}, exceto
 * {@code PIX_ACCOUNT_INACTIVE}, que encerra sem chamar o PSP). Cancelada ou vencida antes do registro não conta. Boleto
 * e link sem conta Pix não têm prestador registrado.
 */
@Component
class JdbcPaymentDataSharingQueries implements PaymentDataSharingQueries {

    private final NamedParameterJdbcTemplate jdbc;

    JdbcPaymentDataSharingQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<DataSharingUsage> pixChargesByAccount(UUID debtorId, Set<UUID> creditorScope) {
        if (creditorScope != null && creditorScope.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource params = new MapSqlParameterSource("debtor", debtorId);
        StringBuilder sql = new StringBuilder("""
                select a.provider as code, a.id as recipient_id, a.name as name,
                       min(i.created_at) as first_at, max(i.created_at) as last_at, count(*) as total,
                       (array_agg(i.id order by i.created_at desc))[1] as latest_id
                  from payment.payment_instruction i
                  join payment.pix_account a on a.id = i.pix_account_id
                 where i.debtor_id = :debtor
                   and (i.registered_at is not null or i.status = 'PAID'
                        or (i.status = 'FAILED' and i.failure_code <> 'PIX_ACCOUNT_INACTIVE'))
                """);
        if (creditorScope != null) {
            sql.append(" and i.creditor_id in (:scope)");
            params.addValue("scope", creditorScope);
        }
        sql.append(" group by a.id, a.provider, a.name order by min(i.created_at)");
        return jdbc.query(sql.toString(), params, DataSharingRows.USAGE);
    }

    @Override
    public List<DataSharingUsage> activeAccounts() {
        return jdbc.query("""
                select provider as code, id as recipient_id, name, created_at as first_at, created_at as last_at,
                       0 as total, null::uuid as latest_id
                  from payment.pix_account where active order by name""", new MapSqlParameterSource(),
                DataSharingRows.USAGE);
    }
}
